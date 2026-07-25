package com.axiom.compliance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * FR-AUD-008 — the enumerable registry of every store erasure must reach.
 *
 * <p>Data model §9 calls erasure "the hardest of these to retrofit" and requires
 * that every derived or cached store be reachable "from day one". The registry is
 * how that becomes checkable instead of aspirational: the erasure process iterates
 * this list rather than a hard-coded sequence of statements, so a store that is
 * added to the platform and not registered shows up as a missing row in a test,
 * and a store that exists but has no adapter shows up on every erasure run as
 * UNREACHABLE with a reason.
 *
 * <p>Not tenant-scoped. Which stores this deployment runs is a deployment fact, not
 * tenant configuration, and a tenant must not be able to shorten the list of
 * places its customers' data is looked for.
 */
@Service
public class ErasableStoreRegistry {

    public record ErasableStore(String storeKey, String label, String storeKind, String adapter,
                                String targetSchema, String targetTable, String subjectMatch,
                                String subjectColumn, List<String> personalColumns, String strategy,
                                List<String> subjectTypes, boolean reachable, String unreachableReason,
                                int sortOrder) {
        public boolean erasable() {
            return reachable && "JDBC_TABLE".equals(adapter)
                    && ("PSEUDONYMISE".equals(strategy) || "DELETE".equals(strategy));
        }
        public boolean appliesTo(String subjectType) {
            return subjectTypes.contains(subjectType);
        }
        public String qualifiedTable() {
            return targetSchema + "." + targetTable;
        }
    }

    private final JdbcTemplate jdbc;

    public ErasableStoreRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ErasableStore> all() {
        return jdbc.query("""
                select store_key, label, store_kind, adapter, target_schema, target_table,
                       subject_match, subject_column, personal_columns, strategy, subject_types,
                       reachable, unreachable_reason, sort_order
                from compliance.erasable_store
                where active = true
                order by sort_order, store_key
                """, (rs, i) -> new ErasableStore(
                rs.getString("store_key"), rs.getString("label"), rs.getString("store_kind"),
                rs.getString("adapter"), rs.getString("target_schema"), rs.getString("target_table"),
                rs.getString("subject_match"), rs.getString("subject_column"),
                textArray(rs.getArray("personal_columns")), rs.getString("strategy"),
                textArray(rs.getArray("subject_types")), rs.getBoolean("reachable"),
                rs.getString("unreachable_reason"), rs.getInt("sort_order")));
    }

    @Transactional(readOnly = true)
    public List<ErasableStore> forSubjectType(String subjectType) {
        return all().stream().filter(store -> store.appliesTo(subjectType)).toList();
    }

    /** FR-AUD-014 SLI input: share of registered stores the process can actually reach. */
    @Transactional(readOnly = true)
    public double coveragePercent() {
        List<ErasableStore> stores = all();
        if (stores.isEmpty()) return 100d;
        long reachable = stores.stream().filter(ErasableStore::reachable).count();
        return (reachable * 100d) / stores.size();
    }

    private static List<String> textArray(java.sql.Array array) {
        if (array == null) return List.of();
        try {
            return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("Registry array column could not be read", ex);
        }
    }
}
