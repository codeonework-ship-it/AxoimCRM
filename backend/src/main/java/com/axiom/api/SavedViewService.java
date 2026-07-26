package com.axiom.api;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Saved list views (FR-UI-020..027).
 *
 * <p>A saved view stores exactly the state the live grid already holds — group
 * columns, column filters, sort, column order — under a name. The correspondence
 * with {@code usePersistedGridState} is the design: if the stored shape and the
 * grid's shape drift apart, applying a view silently drops whatever the grid
 * understands and the view does not, and a view that quietly does less than it
 * did last week is worse than no saved views at all.
 *
 * <h2>Why the definition is validated here</h2>
 * The column is {@code jsonb}, so the database will accept any well-formed JSON.
 * That flexibility is deliberate — the facets a grid supports will grow — but it
 * means the only place that can reject a malformed view is this service. It does
 * so on write rather than on read: a bad view stored today is a bad view every
 * user of a shared view hits tomorrow, and the person who can still fix it is
 * the one saving it.
 *
 * <h2>Sharing</h2>
 * PRIVATE is the owner's alone. SHARED can be applied by anyone in the tenant
 * but changed only by its owner or a tenant administrator. There is deliberately
 * no everyone-can-edit mode: a view a team relies on every morning should not
 * change under them because someone else adjusted a filter and hit Save.
 */
@Service
public class SavedViewService {

    /** Facets a definition may carry. Anything else is a typo or a stale client. */
    private static final Set<String> ALLOWED_FACETS =
            Set.of("groupColumns", "columnFilters", "sort", "columnOrder", "hiddenColumns");

    private static final int MAX_DEFINITION_BYTES = 16_384;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public SavedViewService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    // --------------------------------------------------------------- contracts

    public record SavedViewRequest(
            @NotBlank @Size(max = 80) String gridKey,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 400) String description,
            @Size(max = 20) String visibility,
            Map<String, Object> definition,
            boolean isDefault) {}

    public record SavedView(UUID id, String gridKey, String name, String description,
                            UUID ownerId, String ownerName, String visibility,
                            Map<String, Object> definition, boolean isDefault, boolean editable,
                            Instant createdAt, Instant updatedAt, long version) {}

    // ------------------------------------------------------------------ reading

    /**
     * Every view this user may apply on this grid: their own, plus everything
     * shared in the tenant. Ordered so the default surfaces first and the rest
     * read alphabetically, because a list of views is scanned, not searched.
     */
    @Transactional(readOnly = true)
    public List<SavedView> list(String gridKey) {
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();
        return jdbc.query("""
                select v.id, v.grid_key, v.name, v.description, v.owner_id,
                       u.display_name as owner_name, v.visibility, v.definition::text as definition,
                       v.is_default, v.created_at, v.updated_at, v.version
                from crm.saved_view v
                left join identity.app_user u on u.tenant_id = v.tenant_id and u.id = v.owner_id
                where v.tenant_id = ? and v.deleted_at is null and v.grid_key = ?
                  and (v.owner_id = ? or v.visibility = 'SHARED')
                order by v.is_default desc, lower(v.name)
                """, (rs, i) -> map(rs, me), tenant, gridKey, me);
    }

    @Transactional(readOnly = true)
    public SavedView get(UUID id) {
        UUID me = TenantContext.get().userId();
        try {
            return jdbc.queryForObject("""
                    select v.id, v.grid_key, v.name, v.description, v.owner_id,
                           u.display_name as owner_name, v.visibility, v.definition::text as definition,
                           v.is_default, v.created_at, v.updated_at, v.version
                    from crm.saved_view v
                    left join identity.app_user u on u.tenant_id = v.tenant_id and u.id = v.owner_id
                    where v.tenant_id = ? and v.id = ? and v.deleted_at is null
                      and (v.owner_id = ? or v.visibility = 'SHARED')
                    """, (rs, i) -> map(rs, me), TenantContext.get().tenantId(), id, me);
        } catch (EmptyResultDataAccessException e) {
            // Same answer for "does not exist" and "exists but is someone else's
            // private view" — otherwise a 403 confirms the view is real.
            throw new NotFoundException("That saved view does not exist, or is private to another user");
        }
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public SavedView create(SavedViewRequest request) {
        String name = require(request.name());
        String visibility = visibility(request.visibility());
        String definition = validated(request.definition());
        UUID tenant = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();

        if (request.isDefault()) clearExistingDefault(tenant, me, request.gridKey());

        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into crm.saved_view
                      (tenant_id, grid_key, name, description, owner_id, visibility, definition,
                       is_default, created_by, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    returning id
                    """, UUID.class, tenant, request.gridKey(), name,
                    blankToNull(request.description()), me, visibility, definition,
                    request.isDefault(), me, me);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("You already have a view called \"" + name + "\" on this grid. "
                    + "Pick a different name, or open the existing one and save over it.");
        }

        audit.record("SAVED_VIEW_CREATE", "SAVED_VIEW", id, "Created saved view " + name,
                Map.of("gridKey", request.gridKey(), "visibility", visibility,
                        "isDefault", String.valueOf(request.isDefault())));
        return get(id);
    }

    @Transactional
    public SavedView update(UUID id, long expectedVersion, SavedViewRequest request) {
        SavedView before = get(id);
        assertMayEdit(before);
        String name = require(request.name());
        String visibility = visibility(request.visibility());
        String definition = validated(request.definition());
        UUID tenant = TenantContext.get().tenantId();

        if (request.isDefault()) clearExistingDefault(tenant, before.ownerId(), before.gridKey());

        int updated;
        try {
            updated = jdbc.update("""
                    update crm.saved_view
                    set name = ?, description = ?, visibility = ?, definition = ?::jsonb,
                        is_default = ?, updated_at = now(), updated_by = ?, version = version + 1
                    where tenant_id = ? and id = ? and deleted_at is null and version = ?
                    """, name, blankToNull(request.description()), visibility, definition,
                    request.isDefault(), TenantContext.get().userId(), tenant, id, expectedVersion);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("You already have a view called \"" + name + "\" on this grid.");
        }
        if (updated == 0) {
            throw new ConflictException("This view changed while you were editing it (you had version "
                    + expectedVersion + ", the stored view is version " + before.version()
                    + "). Reload it and re-apply your changes.");
        }

        audit.record("SAVED_VIEW_UPDATE", "SAVED_VIEW", id, "Updated saved view " + name,
                Map.of("gridKey", before.gridKey(), "visibility", visibility,
                        "previousVisibility", before.visibility()));
        return get(id);
    }

    @Transactional
    public void delete(UUID id) {
        SavedView before = get(id);
        assertMayEdit(before);
        jdbc.update("""
                update crm.saved_view
                set deleted_at = now(), deleted_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null
                """, TenantContext.get().userId(), TenantContext.get().tenantId(), id);
        audit.record("SAVED_VIEW_DELETE", "SAVED_VIEW", id, "Deleted saved view " + before.name(),
                Map.of("gridKey", before.gridKey(), "visibility", before.visibility()));
    }

    // ------------------------------------------------------------------ guards

    /**
     * A shared view is applied by everyone and owned by one person. Letting any
     * viewer edit it means the view a team opens each morning can change without
     * anyone deciding it should; tenant administrators are included because
     * somebody has to be able to fix or retire a view whose owner has left.
     */
    private void assertMayEdit(SavedView view) {
        UUID me = TenantContext.get().userId();
        String role = TenantContext.get().role();
        boolean admin = "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        if (!view.ownerId().equals(me) && !admin) {
            throw new ForbiddenException("\"" + view.name() + "\" belongs to "
                    + (view.ownerName() == null ? "another user" : view.ownerName())
                    + ". You can apply it, but only its owner or a tenant administrator can change it. "
                    + "Save your own copy instead.");
        }
    }

    private void clearExistingDefault(UUID tenant, UUID ownerId, String gridKey) {
        jdbc.update("""
                update crm.saved_view set is_default = false, updated_at = now()
                where tenant_id = ? and owner_id = ? and grid_key = ? and is_default and deleted_at is null
                """, tenant, ownerId, gridKey);
    }

    /**
     * Rejects a definition the grid could not apply.
     *
     * <p>An unknown facet is refused rather than dropped. Dropping it would store
     * a view that silently does less than the user asked for, and they would find
     * out the next time they applied it — by which point the grid state that
     * produced it is long gone.
     */
    private String validated(Map<String, Object> definition) {
        Map<String, Object> source = definition == null ? Map.of() : definition;
        List<String> unknown = source.keySet().stream()
                .filter((key) -> !ALLOWED_FACETS.contains(key))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("This view contains settings this grid does not understand: "
                    + String.join(", ", unknown) + ". Supported settings are: "
                    + String.join(", ", ALLOWED_FACETS.stream().sorted().toList()) + ".");
        }

        Map<String, Object> clean = new LinkedHashMap<>();
        for (String facet : source.keySet()) clean.put(facet, source.get(facet));

        String encoded;
        try {
            encoded = json.writeValueAsString(clean);
        } catch (Exception e) {
            throw new IllegalArgumentException("This view could not be stored: its settings are not valid JSON.");
        }
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES) {
            throw new IllegalArgumentException("This view is too large to store. Reduce the number of "
                    + "column filters before saving.");
        }
        return encoded;
    }

    private static String visibility(String value) {
        String normalized = value == null || value.isBlank() ? "PRIVATE" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("PRIVATE") && !normalized.equals("SHARED")) {
            throw new IllegalArgumentException("Visibility must be PRIVATE or SHARED");
        }
        return normalized;
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A view name is required");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SavedView map(java.sql.ResultSet rs, UUID me) throws java.sql.SQLException {
        UUID ownerId = rs.getObject("owner_id", UUID.class);
        Map<String, Object> definition;
        try {
            definition = json.readValue(rs.getString("definition"), new TypeReference<>() {});
        } catch (Exception e) {
            // A view stored before a validation rule existed should not break the
            // whole list; it comes back empty and the user can re-save it.
            definition = Map.of();
        }
        String role = TenantContext.get().role();
        boolean admin = "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        return new SavedView(
                rs.getObject("id", UUID.class), rs.getString("grid_key"), rs.getString("name"),
                rs.getString("description"), ownerId, rs.getString("owner_name"),
                rs.getString("visibility"), definition, rs.getBoolean("is_default"),
                ownerId.equals(me) || admin,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
