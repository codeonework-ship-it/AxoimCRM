package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SCIM 2.0 user provisioning and deprovisioning (FR-TEN-007).
 *
 * <p><b>The rule that shapes everything here: a delete is a deactivation.</b> A
 * directory that removes a leaver would otherwise take their accounts,
 * opportunities and audit trail with them, or leave dangling owner references. So
 * {@code DELETE /scim/v2/Users/{id}} sets {@code active = false}, revokes every
 * session, and returns 204 — the response the directory expects — while the user
 * row and everything attributed to it stays intact. This is FR-TEN-007's explicit
 * rule, and it is also the difference between an integration an enterprise will
 * sign off and one their data-governance review kills.
 *
 * <p>SCIM-provisioned users get an unguessable random password hash rather than a
 * usable credential: they are expected to authenticate through the directory. The
 * column is not-null, and leaving a known or empty value there would be a
 * back door.
 *
 * <p><b>Scope boundary:</b> the endpoints, filters, shapes and semantics are
 * implemented and testable with curl. Interoperability with a specific directory's
 * connector (Entra ID, Okta) is not claimed — those have their own quirks, and
 * proving them needs the vendor.
 */
@Service
public class ScimUserService {

    public static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String PATCH_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String ERROR_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:Error";
    /** Non-standard extension carrying the Axiom role, since SCIM has no equivalent. */
    public static final String AXIOM_EXTENSION = "urn:axiom:params:scim:schemas:extension:2.0:User";

    private static final int MAX_PAGE = 200;

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public ScimUserService(JdbcTemplate jdbc, SessionService sessions, AuditService audit) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> listUsers(String filter, Integer startIndex, Integer count) {
        UUID tenantId = TenantContext.get().tenantId();
        int start = startIndex == null || startIndex < 1 ? 1 : startIndex;
        int size = count == null || count < 1 ? 50 : Math.min(count, MAX_PAGE);
        ParsedFilter parsed = parseFilter(filter);

        StringBuilder where = new StringBuilder(" where u.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (parsed.userName() != null) {
            where.append(" and lower(u.email) = lower(?)");
            args.add(parsed.userName());
        }
        if (parsed.active() != null) {
            where.append(" and u.active = ?");
            args.add(parsed.active());
        }
        Integer total = jdbc.queryForObject(
                "select count(*) from identity.app_user u" + where, Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(start - 1);
        List<Map<String, Object>> resources = jdbc.query("""
                select u.id, u.email, u.display_name, u.role, u.active, u.created_at, u.updated_at,
                       l.external_id, coalesce(l.version, 1) as scim_version
                from identity.app_user u
                left join identity.scim_user_link l on l.tenant_id=u.tenant_id and l.user_id=u.id
                """ + where + " order by u.created_at limit ? offset ?",
                (rs, i) -> toScim(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("role"), rs.getBoolean("active"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("external_id"), rs.getLong("scim_version")),
                pageArgs.toArray());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemas", List.of(LIST_SCHEMA));
        response.put("totalResults", total == null ? 0 : total);
        response.put("startIndex", start);
        response.put("itemsPerPage", resources.size());
        response.put("Resources", resources);
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getUser(UUID id) {
        return fetch(id);
    }

    // ------------------------------------------------------------------
    // Create
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> createUser(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get().tenantId();
        String userName = string(payload.get("userName"));
        if (userName == null) {
            String fromEmails = primaryEmail(payload);
            userName = fromEmails;
        }
        if (userName == null || userName.isBlank()) {
            throw new IllegalArgumentException(
                    "userName is required. Map it to the user's email address in your directory's attribute mapping.");
        }
        String email = primaryEmail(payload) != null ? primaryEmail(payload) : userName;
        if (!email.contains("@")) {
            throw new IllegalArgumentException("The mapped userName/email \"" + email
                    + "\" is not an email address. Axiom identifies users by email.");
        }
        String displayName = displayName(payload, email);
        String role = role(payload);
        boolean active = payload.get("active") == null || Boolean.TRUE.equals(payload.get("active"));

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into identity.app_user
                      (id, tenant_id, email, password_hash, display_name, role, active)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, email.toLowerCase(), unusablePasswordHash(), displayName, role, active);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A user with userName \"" + email + "\" already exists in this workspace. "
                    + "Update that user instead of creating another.");
        }
        jdbc.update("insert into identity.scim_user_link(tenant_id,user_id,external_id) values (?,?,?)",
                tenantId, id, string(payload.get("externalId")));
        audit.record("SCIM_USER_CREATE", "APP_USER", id,
                "User provisioned from the directory: " + email,
                Map.of("source", "SCIM", "userName", email, "role", role, "active", active));
        return fetch(id);
    }

    // ------------------------------------------------------------------
    // Update
    // ------------------------------------------------------------------

    /** PUT semantics: the directory sends the whole resource, so replace what we own. */
    @Transactional
    public Map<String, Object> replaceUser(UUID id, Map<String, Object> payload) {
        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> existing = loadRow(id);
        String email = primaryEmail(payload) != null ? primaryEmail(payload) : string(payload.get("userName"));
        if (email == null) email = (String) existing.get("email");
        String displayName = displayName(payload, email);
        String role = payload.containsKey("roles") || payload.containsKey(AXIOM_EXTENSION)
                ? role(payload) : (String) existing.get("role");
        boolean active = payload.get("active") == null
                ? (Boolean) existing.get("active") : Boolean.TRUE.equals(payload.get("active"));
        try {
            jdbc.update("""
                    update identity.app_user
                    set email = ?, display_name = ?, role = ?, active = ?, updated_at = now()
                    where tenant_id = ? and id = ?
                    """, email.toLowerCase(), displayName, role, active, tenantId, id);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("Another user in this workspace already uses \"" + email + "\".");
        }
        jdbc.update("""
                insert into identity.scim_user_link(tenant_id,user_id,external_id,version)
                values (?,?,?,1)
                on conflict (tenant_id,user_id) do update set
                  external_id=coalesce(excluded.external_id,identity.scim_user_link.external_id),
                  version=identity.scim_user_link.version+1,updated_at=now()
                """, tenantId, id, string(payload.get("externalId")));
        if (!active && Boolean.TRUE.equals(existing.get("active"))) {
            deactivationSideEffects(tenantId, id, (String) existing.get("email"), "SCIM replace set active=false");
        }
        audit.record("SCIM_USER_REPLACE", "APP_USER", id,
                "User replaced from the directory: " + email,
                Map.of("source", "SCIM", "active", active, "role", role));
        return fetch(id);
    }

    /**
     * PATCH with the subset of {@code PatchOp} that directories actually send for
     * lifecycle: {@code replace} on {@code active}, {@code displayName},
     * {@code name.*} and {@code userName}. Anything else is refused with a message
     * naming the unsupported path rather than silently ignored — a directory that
     * believes it disabled an account which is still live is worse than an error.
     */
    @Transactional
    public Map<String, Object> patchUser(UUID id, Map<String, Object> payload) {
        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> existing = loadRow(id);
        Object operations = payload.get("Operations");
        if (!(operations instanceof List<?> ops) || ops.isEmpty()) {
            throw new IllegalArgumentException("A PATCH body must carry a non-empty \"Operations\" array "
                    + "using schema " + PATCH_SCHEMA + ".");
        }
        boolean active = (Boolean) existing.get("active");
        String displayName = (String) existing.get("display_name");
        String email = (String) existing.get("email");
        String role = (String) existing.get("role");

        for (Object rawOp : ops) {
            if (!(rawOp instanceof Map<?, ?> op)) {
                throw new IllegalArgumentException("Each entry in \"Operations\" must be an object");
            }
            String verb = string(op.get("op"));
            String path = string(op.get("path"));
            Object value = op.get("value");
            if (verb == null || !List.of("replace", "add").contains(verb.toLowerCase())) {
                throw new IllegalArgumentException("Only \"replace\" and \"add\" operations are supported; "
                        + "received \"" + verb + "\".");
            }
            if (path == null && value instanceof Map<?, ?> valueMap) {
                // Entra ID sends { op: replace, value: { active: false } } with no path.
                if (valueMap.containsKey("active")) active = truthy(valueMap.get("active"));
                if (valueMap.containsKey("displayName")) displayName = string(valueMap.get("displayName"));
                if (valueMap.containsKey("userName")) email = string(valueMap.get("userName"));
                continue;
            }
            String normalised = path == null ? "" : path.toLowerCase();
            switch (normalised) {
                case "active" -> active = truthy(value);
                case "displayname" -> displayName = string(value);
                case "username" -> email = string(value);
                case "name.formatted" -> displayName = string(value);
                default -> throw new IllegalArgumentException("The attribute path \"" + path
                        + "\" cannot be patched through this endpoint. Supported paths are: active, "
                        + "displayName, userName, name.formatted.");
            }
        }
        jdbc.update("""
                update identity.app_user
                set email = ?, display_name = ?, active = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, email.toLowerCase(), displayName, active, tenantId, id);
        jdbc.update("""
                insert into identity.scim_user_link(tenant_id,user_id,version) values (?,?,2)
                on conflict (tenant_id,user_id) do update set version=identity.scim_user_link.version+1,updated_at=now()
                """, tenantId, id);
        if (!active && Boolean.TRUE.equals(existing.get("active"))) {
            deactivationSideEffects(tenantId, id, email, "SCIM patch set active=false");
        }
        audit.record("SCIM_USER_PATCH", "APP_USER", id,
                "User updated from the directory: " + email,
                Map.of("source", "SCIM", "active", active, "role", role));
        return fetch(id);
    }

    // ------------------------------------------------------------------
    // Deprovision
    // ------------------------------------------------------------------

    /**
     * Deactivates rather than deletes. Returns silently when the user is already
     * inactive: a directory replaying a delete must not see an error.
     */
    @Transactional
    public void deprovisionUser(UUID id) {
        UUID tenantId = TenantContext.get().tenantId();
        Map<String, Object> existing = loadRow(id);
        String email = (String) existing.get("email");
        int owned = ownedRecordCount(tenantId, id);
        jdbc.update("""
                update identity.app_user set active = false, updated_at = now()
                where tenant_id = ? and id = ?
                """, tenantId, id);
        jdbc.update("""
                insert into identity.scim_user_link(tenant_id,user_id,version) values (?,?,2)
                on conflict (tenant_id,user_id) do update set version=identity.scim_user_link.version+1,updated_at=now()
                """, tenantId, id);
        int revoked = deactivationSideEffects(tenantId, id, email, "SCIM delete: user removed in the directory");
        audit.record("SCIM_USER_DEPROVISION", "APP_USER", id,
                "User deactivated from the directory (not deleted): " + email,
                Map.of("source", "SCIM", "sessionsRevoked", revoked, "ownedRecords", owned,
                        "retained", "records they own remain intact and attributed to them"));
    }

    /** Revokes every live session for a user; returns how many were ended. */
    private int deactivationSideEffects(UUID tenantId, UUID userId, String email, String reason) {
        return sessions.revokeAllForUserSystem(tenantId, userId, reason);
    }

    private int ownedRecordCount(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("""
                select (select count(*) from crm.account where tenant_id = ? and owner_id = ?)
                     + (select count(*) from crm.lead where tenant_id = ? and owner_id = ?)
                     + (select count(*) from sales.opportunity where tenant_id = ? and owner_id = ?)
                """, Integer.class, tenantId, userId, tenantId, userId, tenantId, userId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // Shaping
    // ------------------------------------------------------------------

    private Map<String, Object> fetch(UUID id) {
        Map<String, Object> row = loadRow(id);
        return toScim(id, (String) row.get("email"), (String) row.get("display_name"),
                (String) row.get("role"), (Boolean) row.get("active"),
                ((java.sql.Timestamp) row.get("created_at")).toInstant(),
                ((java.sql.Timestamp) row.get("updated_at")).toInstant(),
                (String) row.get("external_id"), ((Number) row.get("scim_version")).longValue());
    }

    private Map<String, Object> loadRow(UUID id) {
        try {
            return jdbc.queryForMap("""
                    select u.id, u.email, u.display_name, u.role, u.active, u.created_at, u.updated_at,
                           l.external_id, coalesce(l.version,1) as scim_version
                    from identity.app_user u
                    left join identity.scim_user_link l on l.tenant_id=u.tenant_id and l.user_id=u.id
                    where u.tenant_id = ? and u.id = ?
                    """, TenantContext.get().tenantId(), id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("No user with that id exists in this workspace");
        }
    }

    private Map<String, Object> toScim(UUID id, String email, String displayName, String role,
                                       boolean active, Instant created, Instant modified,
                                       String externalId, long version) {
        String[] split = splitName(displayName);
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("formatted", displayName);
        name.put("givenName", split[0]);
        name.put("familyName", split[1]);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("resourceType", "User");
        meta.put("created", created.toString());
        meta.put("lastModified", modified.toString());
        meta.put("location", "/scim/v2/Users/" + id);
        meta.put("version", "W/\"" + version + "\"");

        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("role", role);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("schemas", List.of(USER_SCHEMA, AXIOM_EXTENSION));
        resource.put("id", id.toString());
        if (externalId != null) resource.put("externalId", externalId);
        resource.put("userName", email);
        resource.put("name", name);
        resource.put("displayName", displayName);
        resource.put("emails", List.of(Map.of("value", email, "primary", true, "type", "work")));
        resource.put("active", active);
        resource.put(AXIOM_EXTENSION, extension);
        resource.put("meta", meta);
        return resource;
    }

    /** SCIM error envelope, so a client's error handling works as it expects. */
    public static Map<String, Object> error(int status, String detail, String scimType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of(ERROR_SCHEMA));
        if (scimType != null) body.put("scimType", scimType);
        body.put("detail", detail);
        body.put("status", String.valueOf(status));
        return body;
    }

    record ParsedFilter(String userName, Boolean active) {}

    /**
     * Supports the two filters directories actually send: {@code userName eq "x"}
     * and {@code active eq true}. An unsupported filter is an error, not an
     * ignored parameter — silently returning everything would look to the client
     * like "no match" logic it can trust.
     */
    static ParsedFilter parseFilter(String filter) {
        if (filter == null || filter.isBlank()) return new ParsedFilter(null, null);
        String normalised = filter.trim();
        var userNameMatch = java.util.regex.Pattern
                .compile("^userName\\s+eq\\s+\"?([^\"]+)\"?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(normalised);
        if (userNameMatch.matches()) {
            return new ParsedFilter(userNameMatch.group(1), null);
        }
        var activeMatch = java.util.regex.Pattern
                .compile("^active\\s+eq\\s+\"?(true|false)\"?$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(normalised);
        if (activeMatch.matches()) {
            return new ParsedFilter(null, Boolean.parseBoolean(activeMatch.group(1)));
        }
        throw new IllegalArgumentException("Unsupported filter: " + filter
                + ". This endpoint supports 'userName eq \"value\"' and 'active eq true|false'.");
    }

    private static String[] splitName(String displayName) {
        if (displayName == null || displayName.isBlank()) return new String[]{"", ""};
        int space = displayName.trim().lastIndexOf(' ');
        if (space < 0) return new String[]{displayName.trim(), ""};
        return new String[]{displayName.substring(0, space).trim(), displayName.substring(space + 1).trim()};
    }

    private static String displayName(Map<String, Object> payload, String fallbackEmail) {
        String direct = string(payload.get("displayName"));
        if (direct != null && !direct.isBlank()) return direct;
        if (payload.get("name") instanceof Map<?, ?> name) {
            String formatted = string(name.get("formatted"));
            if (formatted != null && !formatted.isBlank()) return formatted;
            String given = string(name.get("givenName"));
            String family = string(name.get("familyName"));
            String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).trim();
            if (!joined.isBlank()) return joined;
        }
        return fallbackEmail == null ? "Directory user"
                : fallbackEmail.contains("@") ? fallbackEmail.substring(0, fallbackEmail.indexOf('@'))
                : fallbackEmail;
    }

    @SuppressWarnings("unchecked")
    private static String primaryEmail(Map<String, Object> payload) {
        Object emails = payload.get("emails");
        if (emails instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("primary"))) {
                    return string(map.get("value"));
                }
            }
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map && map.get("value") != null) {
                    return string(map.get("value"));
                }
            }
        }
        return null;
    }

    private static String role(Map<String, Object> payload) {
        String requested = null;
        if (payload.get(AXIOM_EXTENSION) instanceof Map<?, ?> extension) {
            requested = string(extension.get("role"));
        }
        if (requested == null && payload.get("roles") instanceof List<?> roles && !roles.isEmpty()) {
            Object first = roles.get(0);
            requested = first instanceof Map<?, ?> map ? string(map.get("value")) : string(first);
        }
        if (requested == null || requested.isBlank()) return CrmRole.SALES.name();
        CrmRole role = CrmRole.current(requested.trim().toUpperCase());
        if (role.platform()) {
            throw new ForbiddenException("Platform roles cannot be assigned through directory provisioning");
        }
        return role.name();
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) return bool;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    /**
     * A password hash no presented password can match. SCIM users authenticate
     * through the directory; giving them a derivable local credential would create
     * an alternative sign-in path the directory cannot revoke.
     */
    private String unusablePasswordHash() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return "{scim-no-local-login}" + Base64.getEncoder().encodeToString(raw);
    }
}
