package com.axiom.security;

import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single choke point for record-level authorization.
 *
 * <p><b>Deny by default.</b> Absence of a grant is a denial. Every method here
 * starts from "no" and requires a layer to say yes.
 *
 * <h2>The five layers, and where each is evaluated</h2>
 * <pre>
 *   1 org-wide default    read here, per object, per tenant — the floor
 *   2 role hierarchy      LIVE subquery on the owner's materialized role path
 *   3 sharing rules       LIVE predicate per active rule targeting the caller
 *   4 manual/team/territory  LIVE, plus security.record_share for manual grants
 *   5 field security      not here — FieldSecurityService, at serialization
 * </pre>
 *
 * <h2>Why the predicate evaluates layers 2–4 live instead of trusting
 * {@code record_share}</h2>
 * The sharing model document is explicit that "recomputation must never leave
 * stale access served" and that determinism means either recomputing inside the
 * triggering transaction or having "the query path check live criteria rather
 * than trusting a cache that might be behind" (§3.3). Materialization alone gives
 * a window between a criteria field changing and the recompute landing, during
 * which the old grant is still readable. So the read path derives ownership, role
 * roll-up, live rule criteria, team and territory membership directly, and
 * {@code record_share} contributes manual grants plus whatever the recompute has
 * materialized.
 *
 * <p>{@code record_share} therefore serves two jobs it is uniquely good at: it
 * holds manual and team grants that have no derivation, and it carries
 * {@code cause}/{@code cause_ref} so {@link AccessExplainerService} can answer
 * "why" without re-deriving anything. That is exactly the division of labour data
 * model §3.3 argues for, with the staleness hole closed.
 *
 * <p><b>Owner shares are not materialized.</b> Ownership is a column comparison;
 * writing one {@code record_share} row per record per owner would multiply the
 * highest-volume table in the schema (data model §8) to buy nothing. The explainer
 * reports {@code cause = owner} by deriving it, and says so.
 */
@Service
@Transactional(readOnly = true)
public class AuthorizationService {

    /** Aliases are interpolated into SQL, so they are validated rather than trusted. */
    private static final Pattern SAFE_ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,15}$");

    public enum Access { READ, WRITE }

    /**
     * A composable SQL fragment plus its bind arguments, for callers to drop into
     * the WHERE clause of a list query. Returning a fragment rather than filtering
     * in memory is deliberate: authorization has to be part of the paginated query
     * or the page counts lie.
     */
    public record RecordPredicate(String sql, List<Object> args) {

        static final RecordPredicate DENY_ALL = new RecordPredicate("false", List.of());
        static final RecordPredicate ALLOW_ALL = new RecordPredicate("true", List.of());

        public boolean deniesEverything() { return "false".equals(sql); }

        public boolean allowsEverything() { return "true".equals(sql); }
    }

    private final JdbcTemplate jdbc;
    private final PermissionResolver resolver;

    public AuthorizationService(JdbcTemplate jdbc, PermissionResolver resolver) {
        this.jdbc = jdbc;
        this.resolver = resolver;
    }

    // ------------------------------------------------------------------ decisions

    public boolean canRead(String objectType, UUID recordId) {
        return canRead(SecurableObject.of(objectType), recordId);
    }

    public boolean canRead(SecurableObject object, UUID recordId) {
        return matches(object, recordId, Access.READ);
    }

    public boolean canEdit(String objectType, UUID recordId) {
        return canEdit(SecurableObject.of(objectType), recordId);
    }

    public boolean canEdit(SecurableObject object, UUID recordId) {
        AccessContext ctx = resolver.current();
        if (ctx.readOnlyRole()) return false;
        return matches(ctx, object, recordId, Access.WRITE);
    }

    /**
     * Delete requires the object-level delete right in addition to write-level
     * record access. They are separate rights in FR-SEC-002 and collapsing them
     * would make "can edit" silently include "can destroy".
     */
    public boolean canDelete(String objectType, UUID recordId) {
        return canDelete(SecurableObject.of(objectType), recordId);
    }

    public boolean canDelete(SecurableObject object, UUID recordId) {
        AccessContext ctx = resolver.current();
        if (ctx.readOnlyRole()) return false;
        AccessContext.ObjectAccess access = ctx.access(object);
        if (!access.canDelete() && !access.modifyAll()) return false;
        return matches(ctx, object, recordId, Access.WRITE);
    }

    public boolean canCreate(SecurableObject object) {
        AccessContext ctx = resolver.current();
        return !ctx.readOnlyRole() && ctx.access(object).canCreate();
    }

    /**
     * Reads that fail authorization raise 404, not 403. US-E02-03 requires that a
     * record outside the caller's sharing has "their existence not disclosed" — a
     * 403 tells the caller the id is real, which is the disclosure the requirement
     * forbids.
     */
    public void requireRead(SecurableObject object, UUID recordId) {
        if (!canRead(object, recordId)) throw new NotFoundException(notFound(object));
    }

    /**
     * Writes distinguish the two failures, because they are different problems for
     * the user. A record they cannot see at all is a 404 (same non-disclosure rule).
     * A record they can see but may not change is a 403 that says so, and says what
     * would fix it.
     */
    public void requireEdit(SecurableObject object, UUID recordId) {
        if (canEdit(object, recordId)) return;
        if (!canRead(object, recordId)) throw new NotFoundException(notFound(object));
        throw new ForbiddenException("You have read-only access to this "
                + object.name().toLowerCase(Locale.ROOT) + ". Ask its owner or an administrator for "
                + "read-write access, or for edit permission on your profile.");
    }

    public void requireDelete(SecurableObject object, UUID recordId) {
        if (canDelete(object, recordId)) return;
        if (!canRead(object, recordId)) throw new NotFoundException(notFound(object));
        throw new ForbiddenException("Your profile does not permit deleting a "
                + object.name().toLowerCase(Locale.ROOT) + " record.");
    }

    public void requireCreate(SecurableObject object) {
        if (!canCreate(object)) {
            throw new ForbiddenException("Your profile does not permit creating a "
                    + object.name().toLowerCase(Locale.ROOT) + " record.");
        }
    }

    private static String notFound(SecurableObject object) {
        return "No " + object.name().toLowerCase(Locale.ROOT) + " record with that id is available to you";
    }

    // ------------------------------------------------------------------ predicate

    /** Read-visibility predicate for a list query, using the object's canonical alias. */
    public RecordPredicate visibleRecordPredicate(String objectType) {
        SecurableObject object = SecurableObject.of(objectType);
        return visibleRecordPredicate(object, defaultAlias(object), Access.READ);
    }

    public RecordPredicate visibleRecordPredicate(SecurableObject object, String alias) {
        return visibleRecordPredicate(object, alias, Access.READ);
    }

    public RecordPredicate visibleRecordPredicate(SecurableObject object, String alias, Access mode) {
        return build(resolver.current(), object, alias, mode, 0);
    }

    /** Predicate as it would apply to another user — the explainer needs this. */
    public RecordPredicate visibleRecordPredicateFor(UUID userId, SecurableObject object,
                                                     String alias, Access mode) {
        return build(resolver.forUser(userId), object, alias, mode, 0);
    }

    private static String defaultAlias(SecurableObject object) {
        return switch (object) {
            case ACCOUNT -> "a";
            case CONTACT -> "c";
            case LEAD -> "l";
            case OPPORTUNITY -> "o";
        };
    }

    private RecordPredicate build(AccessContext ctx, SecurableObject object, String alias,
                                  Access mode, int depth) {
        if (!SAFE_ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("Unsafe SQL alias: " + alias);
        }

        AccessContext.ObjectAccess access = ctx.access(object);
        if (mode == Access.READ && !access.canRead()) return RecordPredicate.DENY_ALL;
        if (mode == Access.WRITE) {
            if (ctx.readOnlyRole()) return RecordPredicate.DENY_ALL;
            if (!access.canEdit()) return RecordPredicate.DENY_ALL;
        }
        if (mode == Access.READ && access.viewAll()) return RecordPredicate.ALLOW_ALL;
        if (mode == Access.WRITE && access.modifyAll()) return RecordPredicate.ALLOW_ALL;

        OrgWideDefault owd = orgWideDefault(ctx.tenantId(), object);
        List<String> terms = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        // Layer 1 — the floor. read_write widens for both modes, read_only only for reads.
        if ("read_write".equals(owd.defaultAccess())
                || ("read_only".equals(owd.defaultAccess()) && mode == Access.READ)) {
            return RecordPredicate.ALLOW_ALL;
        }

        // Ownership.
        if (object.hasOwner()) {
            terms.add(alias + "." + object.ownerColumn() + " = ?");
            args.add(ctx.userId());
        } else if (object.parent().isPresent() && depth < 2) {
            // An object with no owner column inherits its parent's visibility. The
            // recursion depth is capped because the registry is a tree today and a
            // cycle in it would be a build defect, not a runtime condition.
            SecurableObject parent = object.parent().orElseThrow();
            String parentAlias = alias + "p";
            RecordPredicate inherited = build(ctx, parent, parentAlias, mode, depth + 1);
            if (!inherited.deniesEverything()) {
                terms.add("exists (select 1 from " + parent.qualifiedTable() + " " + parentAlias
                        + " where " + parentAlias + ".tenant_id = " + alias + ".tenant_id"
                        + " and " + parentAlias + ".id = " + alias + "." + object.parentColumn()
                        + (parent.softDeleted() ? " and " + parentAlias + ".deleted_at is null" : "")
                        + " and (" + inherited.sql() + "))");
                args.addAll(inherited.args());
            }
        }

        // Layer 2 — role hierarchy roll-up. Strict descendants only: peers in the
        // same role do not see each other, which is the difference between a
        // hierarchy and a broadcast.
        if (owd.roleHierarchyRollup() && object.hasOwner() && ctx.rolePath() != null) {
            terms.add("exists (select 1 from security.user_role_assignment ura"
                    + " join security.role_node rn on rn.tenant_id = ura.tenant_id and rn.id = ura.role_node_id"
                    + " where ura.tenant_id = " + alias + ".tenant_id"
                    + " and ura.user_id = " + alias + "." + object.ownerColumn()
                    + " and (ura.expires_at is null or ura.expires_at > now())"
                    + " and rn.active = true and rn.path <> ? and rn.path like ?)");
            args.add(ctx.rolePath());
            args.add(ctx.rolePath() + "%");
        }

        // Layer 3 — sharing rules, evaluated live.
        for (SharingRuleRow rule : applicableRules(ctx, object)) {
            if (mode == Access.WRITE && !"READ_WRITE".equals(rule.accessLevel())) continue;
            RuleTerm term = ruleTerm(object, alias, rule);
            if (term != null) {
                terms.add(term.sql());
                args.addAll(term.args());
            }
        }

        // Layer 4a — record teams.
        terms.add("exists (select 1 from security.record_team_member tm"
                + " where tm.tenant_id = " + alias + ".tenant_id and tm.object_type = ?"
                + " and tm.record_id = " + alias + ".id and tm.user_id = ?"
                + " and (tm.expires_at is null or tm.expires_at > now())"
                + (mode == Access.WRITE ? " and tm.access_level = 'READ_WRITE'" : "") + ")");
        args.add(object.name());
        args.add(ctx.userId());

        // Layer 4b — territory assignment. Bare territory membership conveys read;
        // write through a territory requires an explicit rule or share, so a
        // territory reshuffle cannot silently hand out edit rights.
        if (mode == Access.READ && !ctx.territoryIds().isEmpty()) {
            terms.add("exists (select 1 from security.record_territory rt"
                    + " join security.territory_member tmb on tmb.tenant_id = rt.tenant_id"
                    + " and tmb.territory_id = rt.territory_id"
                    + " where rt.tenant_id = " + alias + ".tenant_id and rt.object_type = ?"
                    + " and rt.record_id = " + alias + ".id and tmb.user_id = ?)");
            args.add(object.name());
            args.add(ctx.userId());
        }

        // Layer 4c — manual shares and anything the recompute materialized.
        terms.add("exists (select 1 from security.record_share rs"
                + " where rs.tenant_id = " + alias + ".tenant_id and rs.object_type = ?"
                + " and rs.record_id = " + alias + ".id and rs.grantee_user_id = ?"
                + " and rs.revoked_at is null and (rs.expires_at is null or rs.expires_at > now())"
                + (mode == Access.WRITE ? " and rs.access_level = 'READ_WRITE'" : "") + ")");
        args.add(object.name());
        args.add(ctx.userId());

        if (terms.isEmpty()) return RecordPredicate.DENY_ALL;
        return new RecordPredicate("(" + String.join("\n   or ", terms) + ")", List.copyOf(args));
    }

    // ------------------------------------------------------------------ layers

    public record OrgWideDefault(String defaultAccess, boolean roleHierarchyRollup) {}

    /**
     * Missing configuration means {@code private}. A tenant with no row for an
     * object must not be readable by everyone because nobody set the default yet.
     */
    public OrgWideDefault orgWideDefault(UUID tenantId, SecurableObject object) {
        OrgWideDefault value = jdbc.query("""
                select default_access, role_hierarchy_rollup from security.org_wide_default
                where tenant_id = ? and object_type = ?
                """, rs -> rs.next() ? new OrgWideDefault(rs.getString(1), rs.getBoolean(2)) : null,
                tenantId, object.name());
        return value != null ? value : new OrgWideDefault("private", true);
    }

    record SharingRuleRow(UUID id, String code, String name, String ruleType,
                          String criteriaField, String criteriaOperator, String criteriaValue,
                          String sourceType, UUID sourceId, String sourcePath,
                          String targetType, UUID targetId, String targetPath,
                          boolean targetIncludesSubordinates, String accessLevel) {}

    /** Active rules on this object whose target includes the given principal. */
    List<SharingRuleRow> applicableRules(AccessContext ctx, SecurableObject object) {
        List<SharingRuleRow> all = jdbc.query("""
                select sr.id, sr.code, sr.name, sr.rule_type, sr.criteria_field, sr.criteria_operator,
                       sr.criteria_value, sr.source_type, sr.source_id, srn.path as source_path,
                       sr.target_type, sr.target_id, trn.path as target_path,
                       sr.target_includes_subordinates, sr.access_level
                from security.sharing_rule sr
                left join security.role_node srn
                       on srn.tenant_id = sr.tenant_id and srn.id = sr.source_id and sr.source_type = 'ROLE'
                left join security.role_node trn
                       on trn.tenant_id = sr.tenant_id and trn.id = sr.target_id and sr.target_type = 'ROLE'
                where sr.tenant_id = ? and sr.object_type = ? and sr.active = true
                order by sr.code
                """, (rs, i) -> new SharingRuleRow(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("rule_type"), rs.getString("criteria_field"),
                        rs.getString("criteria_operator"), rs.getString("criteria_value"),
                        rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                        rs.getString("source_path"), rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class), rs.getString("target_path"),
                        rs.getBoolean("target_includes_subordinates"), rs.getString("access_level")),
                ctx.tenantId(), object.name());
        return all.stream().filter(rule -> targets(ctx, rule)).toList();
    }

    boolean targets(AccessContext ctx, SharingRuleRow rule) {
        return switch (rule.targetType()) {
            case "USER" -> rule.targetId().equals(ctx.userId());
            case "GROUP" -> ctx.groupIds().contains(rule.targetId());
            case "TERRITORY" -> ctx.territoryIds().contains(rule.targetId());
            case "ROLE" -> ctx.rolePath() != null && rule.targetPath() != null
                    && (ctx.rolePath().equals(rule.targetPath())
                        || (rule.targetIncludesSubordinates() && ctx.rolePath().startsWith(rule.targetPath())));
            default -> false;
        };
    }

    record RuleTerm(String sql, List<Object> args) {}

    /** @return the SQL that is true for records this rule grants, or null if the rule cannot be evaluated. */
    RuleTerm ruleTerm(SecurableObject object, String alias, SharingRuleRow rule) {
        if ("CRITERIA".equals(rule.ruleType())) {
            if (!object.hasColumn(rule.criteriaField())) return null;
            String column = alias + "." + object.column(rule.criteriaField());
            String value = rule.criteriaValue();
            return switch (rule.criteriaOperator()) {
                case "EQUALS" -> new RuleTerm("lower(coalesce(" + column + "::text, '')) = lower(?)",
                        List.of(value));
                case "NOT_EQUALS" -> new RuleTerm("lower(coalesce(" + column + "::text, '')) <> lower(?)",
                        List.of(value));
                case "CONTAINS" -> new RuleTerm("lower(coalesce(" + column + "::text, '')) like lower(?)",
                        List.of("%" + value + "%"));
                case "STARTS_WITH" -> new RuleTerm("lower(coalesce(" + column + "::text, '')) like lower(?)",
                        List.of(value + "%"));
                case "IN" -> {
                    List<Object> values = new ArrayList<>();
                    for (String part : value.split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty()) values.add(trimmed.toLowerCase(Locale.ROOT));
                    }
                    if (values.isEmpty()) yield null;
                    yield new RuleTerm("lower(coalesce(" + column + "::text, '')) in ("
                            + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")",
                            values);
                }
                default -> null;
            };
        }

        // OWNER-based: does the record's owner sit in the rule's source population?
        if (!object.hasOwner()) return null;
        String owner = alias + "." + object.ownerColumn();
        return switch (rule.sourceType()) {
            case "ROLE" -> rule.sourcePath() == null ? null : new RuleTerm(
                    "exists (select 1 from security.user_role_assignment sura"
                            + " join security.role_node srn2 on srn2.tenant_id = sura.tenant_id"
                            + " and srn2.id = sura.role_node_id"
                            + " where sura.tenant_id = " + alias + ".tenant_id and sura.user_id = " + owner
                            + " and (sura.expires_at is null or sura.expires_at > now())"
                            + " and srn2.path like ?)",
                    List.of(rule.sourcePath() + "%"));
            case "GROUP" -> new RuleTerm(
                    "exists (select 1 from security.user_group_member sugm"
                            + " where sugm.tenant_id = " + alias + ".tenant_id"
                            + " and sugm.group_id = ? and sugm.user_id = " + owner + ")",
                    List.of(rule.sourceId()));
            case "TERRITORY" -> new RuleTerm(
                    "exists (select 1 from security.territory_member stm"
                            + " where stm.tenant_id = " + alias + ".tenant_id"
                            + " and stm.territory_id = ? and stm.user_id = " + owner + ")",
                    List.of(rule.sourceId()));
            default -> null;
        };
    }

    // ------------------------------------------------------------------ probing

    private boolean matches(SecurableObject object, UUID recordId, Access mode) {
        return matches(resolver.current(), object, recordId, mode);
    }

    private boolean matches(AccessContext ctx, SecurableObject object, UUID recordId, Access mode) {
        if (recordId == null) return false;
        RecordPredicate predicate = build(ctx, object, "t", mode, 0);
        if (predicate.deniesEverything()) return false;
        List<Object> args = new ArrayList<>();
        args.add(ctx.tenantId());
        args.add(recordId);
        args.addAll(predicate.args());
        Boolean found = jdbc.queryForObject("select exists (select 1 from " + object.qualifiedTable() + " t"
                        + " where t.tenant_id = ? and t.id = ?"
                        + (object.softDeleted() ? " and t.deleted_at is null" : "")
                        + " and (" + predicate.sql() + "))",
                Boolean.class, args.toArray());
        return Boolean.TRUE.equals(found);
    }

    /** Does the record exist at all in this tenant, ignoring sharing? Explainer only. */
    boolean recordExists(SecurableObject object, UUID recordId) {
        Boolean found = jdbc.queryForObject("select exists (select 1 from " + object.qualifiedTable() + " t"
                        + " where t.tenant_id = ? and t.id = ?"
                        + (object.softDeleted() ? " and t.deleted_at is null" : "") + ")",
                Boolean.class, com.axiom.tenancy.TenantContext.get().tenantId(), recordId);
        return Boolean.TRUE.equals(found);
    }

    public AccessContext context() {
        return resolver.current();
    }

    /**
     * Guard for administration endpoints. Named permissions rather than role
     * strings so a tenant can move an administrative right onto a permission set
     * without a code change (FR-SEC-003).
     */
    public void requirePermission(String permissionCode, String what) {
        AccessContext ctx = resolver.current();
        if (ctx.platformOperator() && !ctx.readOnlyRole()) return;
        if (ctx.holds(permissionCode)) return;
        throw new ForbiddenException("You do not hold " + permissionCode + ", which is required to " + what);
    }
}
