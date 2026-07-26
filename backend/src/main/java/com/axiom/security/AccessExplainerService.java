package com.axiom.security;

import com.axiom.activity.UserActivityService;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The access explainer (FR-SEC-013): for any user–record pair, every rule that
 * contributes to the answer, and — when the answer is no — why.
 *
 * <h2>Why this is not a nice-to-have</h2>
 * The model has five additive layers, each of which can grant access on its own.
 * "Priya can see this account" has at least six possible reasons and "Priya
 * cannot see this account" has one reason per layer that failed. Without this
 * screen the only way to answer either question is to read the SQL, and the
 * people who need to answer it — an administrator on a support call, an auditor
 * in a review — cannot. A model nobody can interrogate gets configured by
 * guesswork, and guesswork in an authorization model is how records leak.
 *
 * <h2>Grants and denials, not just grants</h2>
 * Every layer is reported with its own verdict, including the ones that did
 * nothing. "No sharing rule matched" is a finding; omitting the layer entirely
 * would leave the reader unable to tell whether it was evaluated.
 *
 * <h2>Evidence versus derivation</h2>
 * Two sources are shown side by side and deliberately not merged:
 *
 * <ul>
 *   <li><b>Live evaluation</b> — the same predicate {@link AuthorizationService}
 *       uses on every query, run for the subject user. This is what access
 *       actually is right now.</li>
 *   <li><b>Materialized {@code security.record_share} rows</b>, each carrying its
 *       {@code cause}. This is the stored answer to "why", captured at grant
 *       time, and it is what makes the explanation cheap rather than a full
 *       recomputation per question.</li>
 * </ul>
 *
 * <p>Where the two disagree the screen shows both, because a divergence means a
 * recompute job is behind — and hiding that behind a merged view would turn a
 * visible staleness bug into an invisible one.
 */
@Service
public class AccessExplainerService {

    /** Layer names, in the order the model evaluates them. */
    private static final String L_OBJECT = "Object permission";
    private static final String L_OWD = "Org-wide default";
    private static final String L_OWNER = "Ownership";
    private static final String L_ROLE = "Role hierarchy";
    private static final String L_RULE = "Sharing rule";
    private static final String L_TEAM = "Record team";
    private static final String L_TERRITORY = "Territory";
    private static final String L_MANUAL = "Manual share";
    private static final String L_FLS = "Field-level security";

    public static final String GRANT = "GRANT";
    public static final String DENY = "DENY";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final PermissionResolver resolver;
    private final UserActivityService activity;

    public AccessExplainerService(JdbcTemplate jdbc, AuthorizationService authorization,
                                  PermissionResolver resolver, UserActivityService activity) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.resolver = resolver;
        this.activity = activity;
    }

    // -----------------------------------------------------------------------

    /**
     * One contributing rule.
     *
     * @param cause the {@code security.record_share.cause} vocabulary
     *              ({@code owner}, {@code role_hierarchy}, {@code sharing_rule},
     *              {@code team}, {@code territory}, {@code manual}) where the
     *              layer has one, so the explanation and the stored evidence use
     *              the same words.
     */
    public record AccessCause(String layer, String cause, String verdict, String accessLevel,
                              String ruleRef, String detail, Instant expiresAt) {}

    public record MaterializedShare(UUID id, String cause, String causeRef, String causeDetail,
                                    String accessLevel, Instant expiresAt, Instant createdAt) {}

    public record AccessExplanation(
            UUID userId, String userEmail, String userDisplayName, String userCrmRole,
            String profileCode, String roleCode, String rolePath,
            String objectType, UUID recordId, boolean recordExists,
            String orgWideDefault, boolean roleHierarchyRollup,
            boolean canRead, boolean canEdit,
            String verdict, String denialReason,
            List<AccessCause> causes,
            List<MaterializedShare> materializedShares,
            List<String> unreadableFields,
            boolean materializationMatchesLiveEvaluation) {}

    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AccessExplanation explain(UUID userId, String objectType, UUID recordId) {
        RbacAccess.requireRead();
        authorization.requirePermission("SYS.VIEW_ACCESS_EXPLAINER",
                "inspect why a user can or cannot see a record");

        SecurableObject object = SecurableObject.of(objectType);
        UUID tenantId = TenantContext.get().tenantId();
        Subject subject = subject(userId);
        AccessContext ctx = resolver.forUser(userId);

        boolean exists = authorization.recordExists(object, recordId);
        AuthorizationService.OrgWideDefault owd = authorization.orgWideDefault(tenantId, object);

        List<AccessCause> causes = new ArrayList<>();
        causes.add(objectPermissionCause(ctx, object));
        causes.add(orgWideDefaultCause(owd));

        UUID ownerId = exists ? ownerOf(object, recordId) : null;
        causes.add(ownershipCause(object, ctx, ownerId));
        causes.add(roleHierarchyCause(object, ctx, ownerId, owd, tenantId));
        causes.addAll(sharingRuleCauses(ctx, object, recordId));
        causes.add(teamCause(object, recordId, userId, tenantId));
        causes.add(territoryCause(object, recordId, ctx, tenantId));
        causes.add(manualShareCause(object, recordId, userId, tenantId));
        causes.add(fieldSecurityCause(ctx, object));

        // Evaluated for the SUBJECT, never for the caller. Using the caller's own
        // context here would make the explainer answer a different question than
        // the one asked, and answer it plausibly.
        boolean canRead = exists && evaluateFor(userId, object, recordId, AuthorizationService.Access.READ);
        boolean canEdit = exists && evaluateFor(userId, object, recordId, AuthorizationService.Access.WRITE);

        List<MaterializedShare> shares = materializedShares(object, recordId, userId, tenantId);
        // Ownership, an open org-wide default and view-all are deliberately not
        // materialized (writing a share row per record per owner would be pure
        // write amplification), so access from those layers is not a divergence.
        boolean consistent = canRead == !shares.isEmpty() || ownedOrBroad(causes);

        String verdict = !exists ? "NO_SUCH_RECORD" : canEdit ? "READ_WRITE" : canRead ? "READ" : "NO_ACCESS";
        String denialReason = denialReason(exists, canRead, canEdit, subject, object, owd, causes);

        // Asking why somebody can see a record is itself a privileged read, and
        // an explainer nobody can audit is a surveillance tool.
        activity.record(new UserActivityService.ActivityEvent(
                tenantId, TenantContext.get().userId(), TenantContext.get().email(),
                TenantContext.get().role(), null, null,
                "SECURITY ACCESS_EXPLAINED", null, null,
                object.name(), recordId, "API",
                canRead ? UserActivityService.SUCCESS : UserActivityService.SUCCESS, 200, null,
                org.slf4j.MDC.get(com.axiom.common.CorrelationIdFilter.MDC_KEY),
                TenantContext.clientIp(), null,
                Map.of("objectType", object.name(),
                       "objectId", recordId.toString(),
                       "targetUserId", userId.toString(),
                       "permission", "SYS.VIEW_ACCESS_EXPLAINER")));

        return new AccessExplanation(
                userId, subject.email(), subject.displayName(), subject.crmRole(),
                ctx.profileCode(), ctx.roleCode(), ctx.rolePath(),
                object.name(), recordId, exists,
                owd.defaultAccess(), owd.roleHierarchyRollup(),
                canRead, canEdit, verdict, denialReason,
                causes, shares, List.copyOf(ctx.unreadable(object)), consistent);
    }

    // ------------------------------------------------------------------ layers

    private AccessCause objectPermissionCause(AccessContext ctx, SecurableObject object) {
        AccessContext.ObjectAccess access = ctx.access(object);
        String detail = "Profile " + ctx.profileCode() + ": read=" + access.canRead()
                + ", edit=" + access.canEdit() + ", delete=" + access.canDelete()
                + ", viewAll=" + access.viewAll() + ", modifyAll=" + access.modifyAll()
                + ", export=" + access.canExport() + ", reveal=" + access.canReveal() + ".";
        if (!access.canRead()) {
            return new AccessCause(L_OBJECT, null, DENY, null, ctx.profileCode(),
                    detail + " Without object read, no sharing layer can grant anything — "
                            + "sharing widens access to records, it does not grant the object.", null);
        }
        if (access.viewAll()) {
            return new AccessCause(L_OBJECT, null, GRANT,
                    access.modifyAll() ? "READ_WRITE" : "READ", ctx.profileCode(),
                    detail + " view-all short-circuits every record layer below.", null);
        }
        return new AccessCause(L_OBJECT, null, GRANT, access.canEdit() ? "READ_WRITE" : "READ",
                ctx.profileCode(), detail, null);
    }

    private AccessCause orgWideDefaultCause(AuthorizationService.OrgWideDefault owd) {
        return switch (owd.defaultAccess()) {
            case "read_write" -> new AccessCause(L_OWD, null, GRANT, "READ_WRITE", null,
                    "The org-wide default is read-write, so every record of this object is open to "
                            + "every user who holds object access.", null);
            case "read_only" -> new AccessCause(L_OWD, null, GRANT, "READ", null,
                    "The org-wide default is read-only: reading needs no further grant, writing does.", null);
            default -> new AccessCause(L_OWD, null, DENY, null, null,
                    "The org-wide default is private, so access must come from a layer below. "
                            + "Role-hierarchy roll-up is " + (owd.roleHierarchyRollup() ? "on" : "off")
                            + " for this object.", null);
        };
    }

    private AccessCause ownershipCause(SecurableObject object, AccessContext ctx, UUID ownerId) {
        if (!object.hasOwner()) {
            return new AccessCause(L_OWNER, "owner", NOT_APPLICABLE, null, null,
                    object.name() + " has no owner column; its visibility follows its parent "
                            + object.parent().map(Enum::name).orElse("record") + ".", null);
        }
        if (ownerId == null) {
            return new AccessCause(L_OWNER, "owner", DENY, null, null,
                    "The record has no owner, so ownership grants nothing.", null);
        }
        if (ownerId.equals(ctx.userId())) {
            return new AccessCause(L_OWNER, "owner", GRANT, "READ_WRITE", ownerId.toString(),
                    "This user owns the record. Ownership is an O(1) column comparison and is "
                            + "deliberately not materialized as a record_share row.", null);
        }
        return new AccessCause(L_OWNER, "owner", DENY, null, ownerId.toString(),
                "The record is owned by another user (" + ownerEmail(ownerId) + ").", null);
    }

    private AccessCause roleHierarchyCause(SecurableObject object, AccessContext ctx, UUID ownerId,
                                           AuthorizationService.OrgWideDefault owd, UUID tenantId) {
        if (!owd.roleHierarchyRollup()) {
            return new AccessCause(L_ROLE, "role_hierarchy", NOT_APPLICABLE, null, null,
                    "Role-hierarchy roll-up is switched off for " + object.name()
                            + ", so a manager does not inherit their team's records of this type.", null);
        }
        if (ctx.rolePath() == null) {
            return new AccessCause(L_ROLE, "role_hierarchy", DENY, null, null,
                    "This user has no place in the role hierarchy, so nothing rolls up to them.", null);
        }
        if (ownerId == null) {
            return new AccessCause(L_ROLE, "role_hierarchy", DENY, null, ctx.rolePath(),
                    "No owner to roll up from.", null);
        }
        if (ownerId.equals(ctx.userId())) {
            return new AccessCause(L_ROLE, "role_hierarchy", NOT_APPLICABLE, null, ctx.rolePath(),
                    "The user is the owner; roll-up is not needed.", null);
        }
        String ownerPath = jdbc.query("""
                select rn.path from security.user_role_assignment ura
                  join security.role_node rn on rn.tenant_id = ura.tenant_id and rn.id = ura.role_node_id
                 where ura.tenant_id = ? and ura.user_id = ?
                   and (ura.expires_at is null or ura.expires_at > now())
                """, rs -> rs.next() ? rs.getString(1) : null, tenantId, ownerId);
        if (ownerPath == null) {
            return new AccessCause(L_ROLE, "role_hierarchy", DENY, null, ctx.rolePath(),
                    "The owner has no role assignment, so they sit beneath nobody.", null);
        }
        boolean beneath = ownerPath.startsWith(ctx.rolePath()) && !ownerPath.equals(ctx.rolePath());
        if (beneath) {
            return new AccessCause(L_ROLE, "role_hierarchy", GRANT, "READ", ctx.rolePath(),
                    "The owner's role " + ownerPath + " sits beneath this user's role "
                            + ctx.rolePath() + ", so the record rolls up.", null);
        }
        return new AccessCause(L_ROLE, "role_hierarchy", DENY, null, ctx.rolePath(),
                "The owner's role " + ownerPath + " is not beneath this user's role " + ctx.rolePath()
                        + ". Roll-up is upward only — siblings do not see each other.", null);
    }

    private List<AccessCause> sharingRuleCauses(AccessContext ctx, SecurableObject object, UUID recordId) {
        List<AuthorizationService.SharingRuleRow> rules = authorization.applicableRules(ctx, object);
        if (rules.isEmpty()) {
            return List.of(new AccessCause(L_RULE, "sharing_rule", DENY, null, null,
                    "No active sharing rule on " + object.name() + " targets this user, their role "
                            + "branch, their groups or their territories.", null));
        }
        List<AccessCause> out = new ArrayList<>();
        for (AuthorizationService.SharingRuleRow rule : rules) {
            AuthorizationService.RuleTerm term = authorization.ruleTerm(object, "t", rule);
            if (term == null) {
                out.add(new AccessCause(L_RULE, "sharing_rule", DENY, rule.accessLevel(), rule.code(),
                        "Rule " + rule.code() + " (" + rule.name() + ") targets this user but cannot be "
                                + "evaluated against " + object.name() + " — its criteria field is not on "
                                + "the object.", null));
                continue;
            }
            List<Object> args = new ArrayList<>();
            args.add(ctx.tenantId());
            args.add(recordId);
            args.addAll(term.args());
            Boolean hit = jdbc.queryForObject(
                    "select exists (select 1 from " + object.qualifiedTable() + " t"
                            + " where t.tenant_id = ? and t.id = ? and (" + term.sql() + "))",
                    Boolean.class, args.toArray());
            boolean matched = Boolean.TRUE.equals(hit);
            out.add(new AccessCause(L_RULE, "sharing_rule", matched ? GRANT : DENY,
                    matched ? rule.accessLevel() : null, rule.code(),
                    "Rule " + rule.code() + " (" + rule.name() + ", " + rule.ruleType().toLowerCase()
                            + "-based) targets this user and " + (matched ? "matches" : "does not match")
                            + " this record.", null));
        }
        return out;
    }

    private AccessCause teamCause(SecurableObject object, UUID recordId, UUID userId, UUID tenantId) {
        return jdbc.query("""
                select access_level, team_role, expires_at
                  from security.record_team_member
                 where tenant_id = ? and object_type = ? and record_id = ? and user_id = ?
                """, rs -> {
            if (!rs.next()) {
                return new AccessCause(L_TEAM, "team", DENY, null, null,
                        "This user is not on the record's team.", null);
            }
            OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
            boolean lapsed = expires != null && expires.toInstant().isBefore(Instant.now());
            return new AccessCause(L_TEAM, "team", lapsed ? DENY : GRANT,
                    lapsed ? null : rs.getString("access_level"), rs.getString("team_role"),
                    lapsed ? "The team membership expired at " + expires + "."
                           : "On the record team as " + rs.getString("team_role") + ".",
                    expires == null ? null : expires.toInstant());
        }, tenantId, object.name(), recordId, userId);
    }

    private AccessCause territoryCause(SecurableObject object, UUID recordId, AccessContext ctx,
                                       UUID tenantId) {
        List<String> shared = jdbc.queryForList("""
                select ter.code
                  from security.record_territory rt
                  join security.territory_member tm on tm.tenant_id = rt.tenant_id
                       and tm.territory_id = rt.territory_id and tm.user_id = ?
                  join security.territory ter on ter.tenant_id = rt.tenant_id and ter.id = rt.territory_id
                 where rt.tenant_id = ? and rt.object_type = ? and rt.record_id = ?
                """, String.class, ctx.userId(), tenantId, object.name(), recordId);
        if (shared.isEmpty()) {
            return new AccessCause(L_TERRITORY, "territory", DENY, null, null,
                    "The record is in no territory this user belongs to.", null);
        }
        return new AccessCause(L_TERRITORY, "territory", GRANT, "READ", String.join(", ", shared),
                "The record and this user share the territory " + String.join(", ", shared) + ".", null);
    }

    private AccessCause manualShareCause(SecurableObject object, UUID recordId, UUID userId,
                                         UUID tenantId) {
        return jdbc.query("""
                select access_level, cause_detail, expires_at
                  from security.record_share
                 where tenant_id = ? and object_type = ? and record_id = ? and grantee_user_id = ?
                   and cause = 'manual' and revoked_at is null
                 order by created_at desc limit 1
                """, rs -> {
            if (!rs.next()) {
                return new AccessCause(L_MANUAL, "manual", DENY, null, null,
                        "No manual share of this record to this user.", null);
            }
            OffsetDateTime expires = rs.getObject("expires_at", OffsetDateTime.class);
            boolean lapsed = expires != null && expires.toInstant().isBefore(Instant.now());
            return new AccessCause(L_MANUAL, "manual", lapsed ? DENY : GRANT,
                    lapsed ? null : rs.getString("access_level"), null,
                    lapsed ? "The manual share expired at " + expires
                             + " and lapsed without any administrator action (FR-SEC-012)."
                           : "Manually shared" + (rs.getString("cause_detail") == null ? "."
                                     : ": " + rs.getString("cause_detail")),
                    expires == null ? null : expires.toInstant());
        }, tenantId, object.name(), recordId, userId);
    }

    private AccessCause fieldSecurityCause(AccessContext ctx, SecurableObject object) {
        var hidden = ctx.unreadable(object);
        if (hidden.isEmpty()) {
            return new AccessCause(L_FLS, null, GRANT, null, ctx.profileCode(),
                    "Every field of " + object.name() + " is readable by this profile.", null);
        }
        return new AccessCause(L_FLS, null, DENY, null, ctx.profileCode(),
                "These fields are removed from every response for this user — absent, not null: "
                        + String.join(", ", hidden) + ".", null);
    }

    // ------------------------------------------------------------------ support

    private List<MaterializedShare> materializedShares(SecurableObject object, UUID recordId,
                                                       UUID userId, UUID tenantId) {
        return jdbc.query("""
                select id, cause, cause_ref, cause_detail, access_level, expires_at, created_at
                  from security.record_share
                 where tenant_id = ? and object_type = ? and record_id = ? and grantee_user_id = ?
                   and revoked_at is null
                 order by created_at
                """, (rs, i) -> new MaterializedShare(
                rs.getObject("id", UUID.class), rs.getString("cause"), rs.getString("cause_ref"),
                rs.getString("cause_detail"), rs.getString("access_level"),
                instant(rs.getObject("expires_at", OffsetDateTime.class)),
                instant(rs.getObject("created_at", OffsetDateTime.class))),
                tenantId, object.name(), recordId, userId);
    }

    private boolean evaluateFor(UUID userId, SecurableObject object, UUID recordId,
                                AuthorizationService.Access mode) {
        AuthorizationService.RecordPredicate predicate =
                authorization.visibleRecordPredicateFor(userId, object, "t", mode);
        if (predicate.deniesEverything()) return false;
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        args.add(recordId);
        args.addAll(predicate.args());
        Boolean found = jdbc.queryForObject(
                "select exists (select 1 from " + object.qualifiedTable() + " t"
                        + " where t.tenant_id = ? and t.id = ?"
                        + (object.softDeleted() ? " and t.deleted_at is null" : "")
                        + " and (" + predicate.sql() + "))",
                Boolean.class, args.toArray());
        return Boolean.TRUE.equals(found);
    }

    /**
     * The denial reason, assembled from the layers that said no. FR-SEC-013
     * requires the explainer to answer the negative case too, and "no access" on
     * its own is not an answer anybody can act on.
     */
    private String denialReason(boolean exists, boolean canRead, boolean canEdit, Subject subject,
                                SecurableObject object, AuthorizationService.OrgWideDefault owd,
                                List<AccessCause> causes) {
        if (!exists) {
            return "No " + object.name().toLowerCase(java.util.Locale.ROOT) + " with that id exists in "
                    + "this workspace, or it has been deleted. Access cannot be evaluated against a "
                    + "record that is not there.";
        }
        if (canRead && canEdit) return null;
        List<String> denials = causes.stream()
                .filter(c -> DENY.equals(c.verdict()) && !L_FLS.equals(c.layer()))
                .map(c -> c.layer() + " — " + c.detail())
                .toList();
        String head = canRead
                ? subject.email() + " can read this record but cannot change it."
                : subject.email() + " has no access to this record.";
        if (denials.isEmpty()) return head;
        return head + " Every layer that declined: " + String.join(" ", denials);
    }

    private boolean ownedOrBroad(List<AccessCause> causes) {
        return causes.stream().anyMatch(c -> GRANT.equals(c.verdict())
                && (L_OWNER.equals(c.layer()) || L_OWD.equals(c.layer())
                    || (L_OBJECT.equals(c.layer()) && c.detail().contains("view-all"))));
    }

    /** Package-private rather than private so the explainer's tests can build one. */
    record Subject(UUID id, String email, String displayName, String crmRole) {}

    private Subject subject(UUID userId) {
        List<Subject> found = jdbc.query("""
                select id, email, display_name, role from identity.app_user
                 where tenant_id = ? and id = ?
                """, (rs, i) -> new Subject(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("display_name"), rs.getString("role")),
                TenantContext.get().tenantId(), userId);
        if (found.isEmpty()) throw new NotFoundException("No such user in this workspace");
        return found.get(0);
    }

    private String ownerEmail(UUID ownerId) {
        String email = jdbc.query("select email from identity.app_user where tenant_id = ? and id = ?",
                rs -> rs.next() ? rs.getString(1) : null, TenantContext.get().tenantId(), ownerId);
        return email == null ? ownerId.toString() : email;
    }

    private UUID ownerOf(SecurableObject object, UUID recordId) {
        if (!object.hasOwner()) {
            if (object.parent().isEmpty()) return null;
            SecurableObject parent = object.parent().orElseThrow();
            UUID parentId = jdbc.query("select " + object.parentColumn() + " from "
                            + object.qualifiedTable() + " where tenant_id = ? and id = ?",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                    TenantContext.get().tenantId(), recordId);
            return parentId == null ? null : ownerOf(parent, parentId);
        }
        return jdbc.query("select " + object.ownerColumn() + " from " + object.qualifiedTable()
                        + " where tenant_id = ? and id = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                TenantContext.get().tenantId(), recordId);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
