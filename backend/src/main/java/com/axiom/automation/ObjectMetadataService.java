package com.axiom.automation;

import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How the rules engine reaches records that belong to every other epic without
 * importing a single one of their classes.
 *
 * <h2>Metadata plus the live catalogue, never a hard-coded table list</h2>
 * An object type resolves through {@code automation.automation_object} to a
 * schema and a table; the columns come from {@code information_schema.columns}
 * at runtime. That second step is the security control, not a convenience: every
 * column name the engine interpolates into SQL has been proved to exist on that
 * exact table first, so a field name coming from a tenant-authored rule can
 * never be anything but a real identifier. Nothing else in this module builds
 * dynamic SQL from a name this class has not vetted.
 *
 * <h2>What it deliberately will not do</h2>
 * It will not write {@code id}, {@code tenant_id}, {@code created_at} or
 * {@code version}, whatever a rule says — those are in
 * {@code protected_columns}. An automation that can rewrite a primary key or a
 * tenant id is a cross-tenant defect waiting for its first rule.
 */
@Service
public class ObjectMetadataService {

    private final JdbcTemplate jdbc;

    /**
     * Column shapes change only by migration, so caching them per
     * schema.table is safe for the life of the process and saves an
     * information_schema round trip on every action.
     */
    private final Map<String, Map<String, String>> columnCache = new ConcurrentHashMap<>();

    @Autowired
    public ObjectMetadataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param columns column name → SQL type, from information_schema
     */
    public record ObjectDescriptor(UUID id, String objectType, String label, String schemaName,
                                   String tableName, String idColumn, String ownerColumn,
                                   String softDeleteColumn, List<String> protectedColumns,
                                   String parentObject, String parentColumn,
                                   Map<String, String> columns) {

        public String qualifiedTable() {
            return schemaName + "." + tableName;
        }

        public boolean writable(String column) {
            return columns.containsKey(column) && !protectedColumns.contains(column);
        }

        public List<String> dateFields() {
            return columns.entrySet().stream()
                    .filter(e -> e.getValue().startsWith("date") || e.getValue().startsWith("timestamp"))
                    .map(Map.Entry::getKey).sorted().toList();
        }
    }

    @Transactional(readOnly = true)
    public List<ObjectDescriptor> list() {
        return jdbc.query("""
                        select id, object_type, label, schema_name, table_name, id_column, owner_column,
                               soft_delete_column, protected_columns, parent_object, parent_column
                        from automation.automation_object
                        where tenant_id = ? and active
                        order by label
                        """,
                (rs, i) -> hydrate(rs), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public ObjectDescriptor describe(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            throw new NotFoundException("An object type is required");
        }
        List<ObjectDescriptor> rows = jdbc.query("""
                        select id, object_type, label, schema_name, table_name, id_column, owner_column,
                               soft_delete_column, protected_columns, parent_object, parent_column
                        from automation.automation_object
                        where tenant_id = ? and object_type = ? and active
                        """,
                (rs, i) -> hydrate(rs), TenantContext.get().tenantId(),
                objectType.trim().toUpperCase(Locale.ROOT));
        if (rows.isEmpty()) {
            throw new NotFoundException("'" + objectType + "' is not an automatable object. "
                    + "Register it in automation.automation_object first.");
        }
        return rows.getFirst();
    }

    private ObjectDescriptor hydrate(java.sql.ResultSet rs) throws java.sql.SQLException {
        String schema = rs.getString("schema_name");
        String table = rs.getString("table_name");
        java.sql.Array protectedArray = rs.getArray("protected_columns");
        List<String> protectedColumns = protectedArray == null ? List.of()
                : List.of((String[]) protectedArray.getArray());
        return new ObjectDescriptor(
                rs.getObject("id", UUID.class), rs.getString("object_type"), rs.getString("label"),
                schema, table, rs.getString("id_column"), rs.getString("owner_column"),
                rs.getString("soft_delete_column"), protectedColumns,
                rs.getString("parent_object"), rs.getString("parent_column"),
                columnsOf(schema, table));
    }

    /** column name → SQL type, straight from the live catalogue. */
    public Map<String, String> columnsOf(String schema, String table) {
        return columnCache.computeIfAbsent(schema + "." + table, key -> {
            Map<String, String> columns = new LinkedHashMap<>();
            jdbc.queryForList("""
                            select column_name, data_type
                            from information_schema.columns
                            where table_schema = ? and table_name = ?
                            order by ordinal_position
                            """, schema, table)
                    .forEach(row -> columns.put(String.valueOf(row.get("column_name")),
                            String.valueOf(row.get("data_type"))));
            return java.util.Collections.unmodifiableMap(columns);
        });
    }

    /**
     * The single gate every dynamic identifier passes through.
     *
     * @throws IllegalArgumentException naming the available columns, so the
     *         administrator gets a usable answer rather than "invalid field"
     */
    public String requireColumn(ObjectDescriptor object, String field) {
        String candidate = field == null ? "" : field.trim();
        if (object.columns().containsKey(candidate)) return candidate;
        String snake = ExpressionEvaluator.toSnake(candidate);
        if (object.columns().containsKey(snake)) return snake;
        throw new IllegalArgumentException("'" + field + "' is not a field of " + object.objectType()
                + ". Fields: " + String.join(", ", object.columns().keySet()));
    }

    public String requireWritableColumn(ObjectDescriptor object, String field) {
        String column = requireColumn(object, field);
        if (object.protectedColumns().contains(column)) {
            throw new IllegalArgumentException("Automation may not write " + object.objectType() + "."
                    + column + "; it is a protected column.");
        }
        return column;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public Map<String, Object> readRecord(String objectType, UUID recordId) {
        return readRecord(describe(objectType), recordId);
    }

    /** @return the record as column → value, or an empty map when it does not exist. */
    public Map<String, Object> readRecord(ObjectDescriptor object, UUID recordId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from " + object.qualifiedTable()
                        + " where tenant_id = ? and " + object.idColumn() + " = ?",
                TenantContext.get().tenantId(), recordId);
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.getFirst());
    }

    /**
     * Children of a record, for a loop step. The foreign key is validated against
     * the child's catalogue entry before it reaches the statement.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> readRelated(String childObjectType, String foreignKeyField,
                                                 UUID parentId, int limit) {
        ObjectDescriptor child = describe(childObjectType);
        String fk = requireColumn(child, foreignKeyField);
        String sql = "select * from " + child.qualifiedTable()
                + " where tenant_id = ? and " + fk + " = ?"
                + (child.softDeleteColumn() == null ? "" : " and " + child.softDeleteColumn() + " is null")
                + " order by " + child.idColumn() + " limit " + Math.max(1, Math.min(limit, 500));
        return new ArrayList<>(jdbc.queryForList(sql, TenantContext.get().tenantId(), parentId));
    }

    /** Records matching a scheduled rule's date-field window. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> readByDateOffset(ObjectDescriptor object, String dateField,
                                                      int offsetDays, int limit) {
        String column = requireColumn(object, dateField);
        String sql = "select * from " + object.qualifiedTable()
                + " where tenant_id = ? and " + column + " is not null"
                + " and (" + column + ")::date = (current_date - (? || ' days')::interval)::date"
                + (object.softDeleteColumn() == null ? "" : " and " + object.softDeleteColumn() + " is null")
                + " order by " + object.idColumn() + " limit " + Math.max(1, Math.min(limit, 1000));
        return new ArrayList<>(jdbc.queryForList(sql, TenantContext.get().tenantId(),
                String.valueOf(offsetDays)));
    }

    /** A page of records of one type, for choosing simulation subjects. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sample(String objectType, int limit) {
        ObjectDescriptor object = describe(objectType);
        String sql = "select * from " + object.qualifiedTable()
                + " where tenant_id = ?"
                + (object.softDeleteColumn() == null ? "" : " and " + object.softDeleteColumn() + " is null")
                + " order by " + object.idColumn() + " limit " + Math.max(1, Math.min(limit, 200));
        return new ArrayList<>(jdbc.queryForList(sql, TenantContext.get().tenantId()));
    }
}
