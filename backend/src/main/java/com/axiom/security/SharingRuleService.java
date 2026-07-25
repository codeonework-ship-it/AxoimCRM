package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sharing rules (FR-SEC-005), manual and team shares (FR-SEC-006), and the
 * materialization of {@code security.record_share}.
 *
 * <h2>Recomputation is incremental, asynchronous, and cannot block a business write</h2>
 * Data model §8 is explicit: {@code RECORD_SHARE} grows super-linearly with users ×
 * records, so "recomputation on role or rule change must be incremental and
 * asynchronous, with progress visible; a full rebuild must never block business
 * writes". So a business write does exactly one thing — insert a row into
 * {@code sharing_recompute_job} — and a poller drains that queue in bounded batches,
 * updating {@code processed_units} as it goes. The progress is a real count of real
 * work, readable from the sharing screen, not a spinner.
 *
 * <h2>Why asynchronous materialization is nonetheless safe</h2>
 * Because {@link AuthorizationService} does not depend on it for correctness. The
 * read path evaluates rule criteria, ownership, role roll-up, team and territory
 * live, so a job that has not run yet cannot serve stale access and cannot withhold
 * access it should have granted. That is the resolution of the apparent conflict in
 * §3.3 between "recomputation must be deterministic" and "recomputation is
 * asynchronous": materialization is for explanation and reporting, live evaluation is
 * for enforcement.
 *
 * <h2>Generational revocation</h2>
 * Each job stamps the shares it writes with its own id in {@code cause_detail}, then
 * revokes any share for that rule carrying an older stamp. A delete-then-insert pass
 * would blink access off for everyone mid-rebuild; a generational sweep never does.
 */
@Service
public class SharingRuleService {

    private static final Logger log = LoggerFactory.getLogger(SharingRuleService.class);

    public record SharingRule(UUID id, String code, String name, String objectType, String ruleType,
                              String criteriaField, String criteriaOperator, String criteriaValue,
                              String sourceType, UUID sourceId, String sourceLabel,
                              String targetType, UUID targetId, String targetLabel,
                              boolean targetIncludesSubordinates, String accessLevel,
                              boolean active, Instant lastRecomputedAt, long materializedShares) {}

    public record SharingRuleRequest(@NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]*$") String code,
                                     @NotBlank String name, @NotBlank String objectType,
                                     @NotBlank String ruleType,
                                     String criteriaField, String criteriaOperator, String criteriaValue,
                                     String sourceType, UUID sourceId,
                                     @NotBlank String targetType, @NotNull UUID targetId,
                                     Boolean targetIncludesSubordinates,
                                     @NotBlank String accessLevel) {}

    public record ManualShareRequest(@NotBlank String objectType, @NotNull UUID recordId,
                                     @NotNull UUID granteeUserId, @NotBlank String accessLevel,
                                     Instant expiresAt, String reason) {}

    public record TeamMemberRequest(@NotBlank String objectType, @NotNull UUID recordId,
                                    @NotNull UUID userId, String teamRole,
                                    @NotBlank String accessLevel, Instant expiresAt) {}

    public record RecordShareRow(UUID id, String objectType, UUID recordId, UUID granteeUserId,
                                 String granteeEmail, String accessLevel, String cause, String causeRef,
                                 Instant expiresAt, Instant createdAt) {}

    public record RecomputeJob(UUID id, String objectType, String scope, UUID scopeRef,
                               String triggerReason, String status, int totalUnits, int processedUnits,
                               int sharesWritten, int sharesRevoked, Instant queuedAt,
                               Instant startedAt, Instant finishedAt, String message) {}

    private final JdbcTemplate jdbc;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final SystemTaskRunner tasks;
    private final int batchSize;

    public SharingRuleService(JdbcTemplate jdbc, AuthorizationService authorization, AuditService audit,
                              SystemTaskRunner tasks,
                              @Value("${axiom.security.recompute-batch-size:500}") int batchSize) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.audit = audit;
        this.tasks = tasks;
        this.batchSize = Math.max(50, batchSize);
    }

    // ------------------------------------------------------------------ rules

    @Transactional(readOnly = true)
    public List<SharingRule> rules() {
        return jdbc.query("""
                select sr.id, sr.code, sr.name, sr.object_type, sr.rule_type, sr.criteria_field,
                       sr.criteria_operator, sr.criteria_value, sr.source_type, sr.source_id,
                       coalesce(srn.code, sug.code, ster.code, '') as source_label,
                       sr.target_type, sr.target_id,
                       coalesce(trn.code, tug.code, tter.code, tu.email, '') as target_label,
                       sr.target_includes_subordinates, sr.access_level, sr.active, sr.last_recomputed_at,
                       (select count(*) from security.record_share rs
                        where rs.tenant_id = sr.tenant_id and rs.cause = 'sharing_rule'
                          and rs.cause_ref = sr.code and rs.revoked_at is null) as materialized
                from security.sharing_rule sr
                left join security.role_node srn on srn.tenant_id = sr.tenant_id and srn.id = sr.source_id
                left join security.user_group sug on sug.tenant_id = sr.tenant_id and sug.id = sr.source_id
                left join security.territory ster on ster.tenant_id = sr.tenant_id and ster.id = sr.source_id
                left join security.role_node trn on trn.tenant_id = sr.tenant_id and trn.id = sr.target_id
                left join security.user_group tug on tug.tenant_id = sr.tenant_id and tug.id = sr.target_id
                left join security.territory tter on tter.tenant_id = sr.tenant_id and tter.id = sr.target_id
                left join identity.app_user tu on tu.tenant_id = sr.tenant_id and tu.id = sr.target_id
                where sr.tenant_id = ?
                order by sr.object_type, sr.code
                """, (rs, i) -> new SharingRule(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("object_type"), rs.getString("rule_type"),
                        rs.getString("criteria_field"), rs.getString("criteria_operator"),
                        rs.getString("criteria_value"), rs.getString("source_type"),
                        rs.getObject("source_id", UUID.class), rs.getString("source_label"),
                        rs.getString("target_type"), rs.getObject("target_id", UUID.class),
                        rs.getString("target_label"), rs.getBoolean("target_includes_subordinates"),
                        rs.getString("access_level"), rs.getBoolean("active"),
                        rs.getTimestamp("last_recomputed_at") == null ? null
                                : rs.getTimestamp("last_recomputed_at").toInstant(),
                        rs.getLong("materialized")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public SharingRule define(SharingRuleRequest request) {
        authorization.requirePermission("SYS.MANAGE_SHARING", "define a sharing rule");
        UUID tenantId = TenantContext.get().tenantId();
        SecurableObject object = SecurableObject.of(request.objectType());
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        String ruleType = request.ruleType().trim().toUpperCase(Locale.ROOT);
        String accessLevel = request.accessLevel().trim().toUpperCase(Locale.ROOT);

        if (!Set.of("CRITERIA", "OWNER").contains(ruleType)) {
            throw new ConflictException("A sharing rule is either CRITERIA-based or OWNER-based.");
        }
        if (!Set.of("READ", "READ_WRITE").contains(accessLevel)) {
            throw new ConflictException("Access level must be READ or READ_WRITE. Sharing only ever "
                    + "widens access; to narrow it, lower the org-wide default.");
        }
        if ("CRITERIA".equals(ruleType)) {
            if (request.criteriaField() == null || !object.hasColumn(request.criteriaField())) {
                throw new NotFoundException("'" + request.criteriaField() + "' is not a field of "
                        + object.name() + ". Known fields: " + String.join(", ", object.fieldNames()));
            }
        } else if (request.sourceType() == null || request.sourceId() == null) {
            throw new ConflictException("An owner-based rule needs a source role, group or territory.");
        } else if (!object.hasOwner()) {
            throw new ConflictException(object.name() + " records have no owner of their own — their "
                    + "visibility follows their parent account. Write the rule against ACCOUNT instead.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.sharing_rule
                  (id, tenant_id, code, name, object_type, rule_type, criteria_field, criteria_operator,
                   criteria_value, source_type, source_id, target_type, target_id,
                   target_includes_subordinates, access_level, active, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?)
                """, id, tenantId, code, request.name(), object.name(), ruleType,
                request.criteriaField(),
                request.criteriaOperator() == null ? null : request.criteriaOperator().toUpperCase(Locale.ROOT),
                request.criteriaValue(),
                request.sourceType() == null ? null : request.sourceType().toUpperCase(Locale.ROOT),
                request.sourceId(), request.targetType().toUpperCase(Locale.ROOT), request.targetId(),
                request.targetIncludesSubordinates() == null || request.targetIncludesSubordinates(),
                accessLevel, TenantContext.get().userId());

        audit.record("SHARING_RULE_DEFINED", object.name(), id, "Defined sharing rule " + code,
                Map.of("code", code, "ruleType", ruleType, "accessLevel", accessLevel,
                        "targetType", request.targetType(), "active", false));
        return find(id);
    }

    /**
     * Activation is the moment a rule starts granting access, so it is audited
     * separately from definition and it enqueues the materialization pass. Rules are
     * created inactive on purpose: defining a rule and turning it on are different
     * decisions, often by different people.
     */
    @Transactional
    public SharingRule activate(UUID ruleId, boolean active) {
        authorization.requirePermission("SYS.MANAGE_SHARING", "activate or deactivate a sharing rule");
        SharingRule rule = find(ruleId);
        jdbc.update("""
                update security.sharing_rule
                set active = ?, activated_at = case when ? then now() else activated_at end,
                    activated_by = case when ? then ? else activated_by end
                where tenant_id = ? and id = ?
                """, active, active, active, TenantContext.get().userId(),
                TenantContext.get().tenantId(), ruleId);

        audit.record(active ? "SHARING_RULE_ACTIVATED" : "SHARING_RULE_DEACTIVATED",
                rule.objectType(), ruleId,
                (active ? "Activated" : "Deactivated") + " sharing rule " + rule.code(),
                Map.of("before", Map.of("active", rule.active()), "after", Map.of("active", active),
                        "accessLevel", rule.accessLevel()));

        enqueue(rule.objectType(), "RULE", ruleId,
                (active ? "Rule activated: " : "Rule deactivated: ") + rule.code());
        return find(ruleId);
    }

    @Transactional(readOnly = true)
    public SharingRule find(UUID id) {
        return rules().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("No sharing rule with that id in this tenant"));
    }

    // ------------------------------------------------------------------ manual and team shares

    /**
     * A manual share. Requires the sharer to be able to write the record — you cannot
     * give away access you do not have — or to hold the sharing administration right.
     */
    @Transactional
    public RecordShareRow shareManually(ManualShareRequest request) {
        SecurableObject object = SecurableObject.of(request.objectType());
        AccessContext ctx = authorization.context();
        boolean admin = ctx.holds("SYS.MANAGE_SHARING") || ctx.platformOperator();
        if (!admin && !authorization.canEdit(object, request.recordId())) {
            authorization.requireRead(object, request.recordId());
            throw new com.axiom.common.ForbiddenException("You need read-write access to a "
                    + object.name().toLowerCase(Locale.ROOT) + " before you can share it with somebody else.");
        }
        String accessLevel = request.accessLevel().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("READ", "READ_WRITE").contains(accessLevel)) {
            throw new ConflictException("Access level must be READ or READ_WRITE.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.record_share
                  (id, tenant_id, object_type, record_id, grantee_user_id, access_level, cause,
                   cause_ref, cause_detail, expires_at, created_by)
                values (?, ?, ?, ?, ?, ?, 'manual', ?, ?, ?, ?)
                on conflict (tenant_id, object_type, record_id, grantee_user_id, cause, coalesce(cause_ref, ''))
                where revoked_at is null
                do update set access_level = excluded.access_level, expires_at = excluded.expires_at
                """, id, ctx.tenantId(), object.name(), request.recordId(), request.granteeUserId(),
                accessLevel, TenantContext.get().userId().toString(),
                request.reason() == null ? "Manual share" : request.reason(),
                request.expiresAt() == null ? null : java.sql.Timestamp.from(request.expiresAt()),
                ctx.userId());

        audit.recordWithReason("RECORD_SHARED", object.name(), request.recordId(),
                "Shared with a colleague at " + accessLevel, request.reason(),
                Map.of("granteeUserId", request.granteeUserId().toString(), "accessLevel", accessLevel,
                        "expiresAt", request.expiresAt() == null ? "never" : request.expiresAt().toString(),
                        "cause", "manual"));
        return sharesOf(object.name(), request.recordId()).stream()
                .filter(s -> s.granteeUserId().equals(request.granteeUserId()) && "manual".equals(s.cause()))
                .findFirst().orElseThrow();
    }

    @Transactional
    public void revokeManualShare(UUID shareId, String reason) {
        UUID tenantId = TenantContext.get().tenantId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select object_type, record_id, grantee_user_id, cause from security.record_share
                where tenant_id = ? and id = ? and revoked_at is null
                """, tenantId, shareId);
        if (rows.isEmpty()) throw new NotFoundException("No live share with that id");
        Map<String, Object> row = rows.getFirst();
        if (!"manual".equals(row.get("cause"))) {
            throw new ConflictException("Only manual shares can be revoked directly. A share caused by "
                    + row.get("cause") + " is removed by changing the rule, role or team that causes it.");
        }
        jdbc.update("""
                update security.record_share set revoked_at = now(), revoked_by = ?
                where tenant_id = ? and id = ?
                """, TenantContext.get().userId(), tenantId, shareId);
        audit.recordWithReason("RECORD_SHARE_REVOKED", String.valueOf(row.get("object_type")),
                (UUID) row.get("record_id"), "Revoked a manual share", reason,
                Map.of("shareId", shareId.toString()));
    }

    @Transactional
    public void addTeamMember(TeamMemberRequest request) {
        SecurableObject object = SecurableObject.of(request.objectType());
        authorization.requireEdit(object, request.recordId());
        String accessLevel = request.accessLevel().trim().toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into security.record_team_member
                  (tenant_id, object_type, record_id, user_id, team_role, access_level, expires_at, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, object_type, record_id, user_id) do update
                set team_role = excluded.team_role, access_level = excluded.access_level,
                    expires_at = excluded.expires_at
                """, TenantContext.get().tenantId(), object.name(), request.recordId(), request.userId(),
                request.teamRole() == null ? "MEMBER" : request.teamRole(), accessLevel,
                request.expiresAt() == null ? null : java.sql.Timestamp.from(request.expiresAt()),
                TenantContext.get().userId());

        // Materialized alongside the team row so the explainer can name `team` as a
        // cause without joining across to the roster.
        jdbc.update("""
                insert into security.record_share
                  (tenant_id, object_type, record_id, grantee_user_id, access_level, cause,
                   cause_ref, cause_detail, expires_at, created_by)
                values (?, ?, ?, ?, ?, 'team', ?, ?, ?, ?)
                on conflict (tenant_id, object_type, record_id, grantee_user_id, cause, coalesce(cause_ref, ''))
                where revoked_at is null
                do update set access_level = excluded.access_level, expires_at = excluded.expires_at
                """, TenantContext.get().tenantId(), object.name(), request.recordId(), request.userId(),
                accessLevel, "record-team",
                "Record team role: " + (request.teamRole() == null ? "MEMBER" : request.teamRole()),
                request.expiresAt() == null ? null : java.sql.Timestamp.from(request.expiresAt()),
                TenantContext.get().userId());

        audit.record("RECORD_TEAM_MEMBER_ADDED", object.name(), request.recordId(),
                "Added a record team member at " + accessLevel,
                Map.of("userId", request.userId().toString(), "accessLevel", accessLevel));
    }

    @Transactional(readOnly = true)
    public List<RecordShareRow> sharesOf(String objectType, UUID recordId) {
        SecurableObject object = SecurableObject.of(objectType);
        return jdbc.query("""
                select rs.id, rs.object_type, rs.record_id, rs.grantee_user_id, u.email, rs.access_level,
                       rs.cause, rs.cause_ref, rs.expires_at, rs.created_at
                from security.record_share rs
                join identity.app_user u on u.tenant_id = rs.tenant_id and u.id = rs.grantee_user_id
                where rs.tenant_id = ? and rs.object_type = ? and rs.record_id = ? and rs.revoked_at is null
                order by rs.cause, u.email
                """, (rs, i) -> new RecordShareRow(rs.getObject("id", UUID.class),
                        rs.getString("object_type"), rs.getObject("record_id", UUID.class),
                        rs.getObject("grantee_user_id", UUID.class), rs.getString("email"),
                        rs.getString("access_level"), rs.getString("cause"), rs.getString("cause_ref"),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()),
                TenantContext.get().tenantId(), object.name(), recordId);
    }

    // ------------------------------------------------------------------ recompute queue

    /**
     * Enqueue work. This is all a business write does — one insert, no scan, no lock
     * on the sharing tables. That is the property data model §8 demands.
     */
    @Transactional
    public UUID enqueue(String objectType, String scope, UUID scopeRef, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.sharing_recompute_job
                  (id, tenant_id, object_type, scope, scope_ref, trigger_reason, requested_by)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, TenantContext.get().tenantId(), SecurableObject.of(objectType).name(),
                scope, scopeRef, reason,
                TenantContext.isBound() ? TenantContext.get().userId() : null);
        return id;
    }

    /** Owner changed on a record: owner-based rules for that record need recomputing. */
    @Transactional
    public void onOwnerChanged(SecurableObject object, UUID recordId) {
        enqueue(object.name(), "OWNER_CHANGE", recordId, "Record owner changed");
    }

    /** A record was saved: criteria-based rules for that record need recomputing. */
    @Transactional
    public void onRecordSaved(SecurableObject object, UUID recordId) {
        enqueue(object.name(), "RECORD", recordId, "Record criteria fields may have changed");
    }

    /** Role membership changed: every owner-based rule on every object is affected. */
    @Transactional
    public void onRoleMembershipChanged(UUID userId) {
        for (SecurableObject object : SecurableObject.values()) {
            enqueue(object.name(), "ROLE_MEMBERSHIP", userId, "Role membership changed");
        }
    }

    @Transactional(readOnly = true)
    public List<RecomputeJob> jobs(int limit) {
        int safe = Math.max(1, Math.min(limit, 200));
        return jdbc.query("""
                select id, object_type, scope, scope_ref, trigger_reason, status, total_units,
                       processed_units, shares_written, shares_revoked, queued_at, started_at,
                       finished_at, message
                from security.sharing_recompute_job where tenant_id = ?
                order by queued_at desc limit ?
                """, (rs, i) -> new RecomputeJob(rs.getObject("id", UUID.class),
                        rs.getString("object_type"), rs.getString("scope"),
                        rs.getObject("scope_ref", UUID.class), rs.getString("trigger_reason"),
                        rs.getString("status"), rs.getInt("total_units"), rs.getInt("processed_units"),
                        rs.getInt("shares_written"), rs.getInt("shares_revoked"),
                        rs.getTimestamp("queued_at").toInstant(),
                        rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
                        rs.getString("message")),
                TenantContext.get().tenantId(), safe);
    }

    /** Run the queue for the caller's tenant now, and report progress. Used by the UI's "recompute" button. */
    @Transactional
    public int drainNow() {
        authorization.requirePermission("SYS.MANAGE_SHARING", "run a sharing recomputation");
        return drainTenant(TenantContext.get().tenantId());
    }

    @Scheduled(fixedDelayString = "${axiom.security.recompute-poll-ms:15000}")
    public void pollQueue() {
        try {
            tasks.forEachTenant("Sharing recompute", this::drainTenant);
        } catch (RuntimeException ex) {
            log.error("Sharing recompute poll failed", ex);
        }
    }

    int drainTenant(UUID tenantId) {
        List<RecomputeJob> queued = jdbc.query("""
                select id, object_type, scope, scope_ref, trigger_reason, status, total_units,
                       processed_units, shares_written, shares_revoked, queued_at, started_at,
                       finished_at, message
                from security.sharing_recompute_job
                where tenant_id = ? and status in ('QUEUED','RUNNING')
                order by queued_at limit 20
                """, (rs, i) -> new RecomputeJob(rs.getObject("id", UUID.class),
                        rs.getString("object_type"), rs.getString("scope"),
                        rs.getObject("scope_ref", UUID.class), rs.getString("trigger_reason"),
                        rs.getString("status"), rs.getInt("total_units"), rs.getInt("processed_units"),
                        rs.getInt("shares_written"), rs.getInt("shares_revoked"),
                        rs.getTimestamp("queued_at").toInstant(), null, null, null),
                tenantId);
        int processed = 0;
        for (RecomputeJob job : queued) {
            try {
                processed += runJob(tenantId, job);
            } catch (RuntimeException ex) {
                log.error("Sharing recompute job {} failed", job.id(), ex);
                jdbc.update("""
                        update security.sharing_recompute_job
                        set status = 'FAILED', finished_at = now(), message = ?
                        where tenant_id = ? and id = ?
                        """, ex.getMessage(), tenantId, job.id());
            }
        }
        return processed;
    }

    private int runJob(UUID tenantId, RecomputeJob job) {
        jdbc.update("""
                update security.sharing_recompute_job
                set status = 'RUNNING', started_at = coalesce(started_at, now())
                where tenant_id = ? and id = ?
                """, tenantId, job.id());

        SecurableObject object = SecurableObject.of(job.objectType());
        int written = 0;
        int revoked = 0;

        switch (job.scope()) {
            case "RULE" -> {
                if (job.scopeRef() != null) {
                    int[] counts = recomputeRule(tenantId, job, object, job.scopeRef());
                    written = counts[0];
                    revoked = counts[1];
                }
            }
            case "RECORD", "OWNER_CHANGE" -> {
                if (job.scopeRef() != null) {
                    int[] counts = recomputeRecord(tenantId, object, job.scopeRef());
                    written = counts[0];
                    revoked = counts[1];
                }
                complete(tenantId, job.id(), 1, 1, written, revoked, "Recomputed one record");
                return 1;
            }
            case "ROLE_MEMBERSHIP", "FULL" -> {
                // Fan out into one job per rule so each is independently batched and
                // independently visible, rather than one opaque long-running task.
                List<UUID> ruleIds = jdbc.query("""
                        select id from security.sharing_rule
                        where tenant_id = ? and object_type = ? and active = true
                        """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, object.name());
                for (UUID ruleId : ruleIds) {
                    jdbc.update("""
                            insert into security.sharing_recompute_job
                              (tenant_id, object_type, scope, scope_ref, trigger_reason)
                            values (?, ?, 'RULE', ?, ?)
                            """, tenantId, object.name(), ruleId,
                            "Fan-out from " + job.scope() + ": " + job.triggerReason());
                }
                complete(tenantId, job.id(), ruleIds.size(), ruleIds.size(), 0, 0,
                        "Fanned out into " + ruleIds.size() + " rule job(s)");
                return ruleIds.size();
            }
            default -> complete(tenantId, job.id(), 0, 0, 0, 0, "Unknown scope " + job.scope());
        }
        return written + revoked;
    }

    /**
     * One batch of one rule. Returns {written, revoked}. The job stays RUNNING with
     * its progress updated until the offset passes the record count, so a large rule
     * is visibly making progress rather than appearing hung.
     */
    private int[] recomputeRule(UUID tenantId, RecomputeJob job, SecurableObject object, UUID ruleId) {
        List<AuthorizationService.SharingRuleRow> rows = jdbc.query("""
                select sr.id, sr.code, sr.name, sr.rule_type, sr.criteria_field, sr.criteria_operator,
                       sr.criteria_value, sr.source_type, sr.source_id, srn.path as source_path,
                       sr.target_type, sr.target_id, trn.path as target_path,
                       sr.target_includes_subordinates, sr.access_level, sr.active
                from security.sharing_rule sr
                left join security.role_node srn
                       on srn.tenant_id = sr.tenant_id and srn.id = sr.source_id and sr.source_type = 'ROLE'
                left join security.role_node trn
                       on trn.tenant_id = sr.tenant_id and trn.id = sr.target_id and sr.target_type = 'ROLE'
                where sr.tenant_id = ? and sr.id = ?
                """, (rs, i) -> new AuthorizationService.SharingRuleRow(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getString("rule_type"), rs.getString("criteria_field"),
                        rs.getString("criteria_operator"), rs.getString("criteria_value"),
                        rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                        rs.getString("source_path"), rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class), rs.getString("target_path"),
                        rs.getBoolean("target_includes_subordinates"), rs.getString("access_level")),
                tenantId, ruleId);
        if (rows.isEmpty()) {
            complete(tenantId, job.id(), 0, 0, 0, 0, "Rule no longer exists");
            return new int[]{0, 0};
        }
        AuthorizationService.SharingRuleRow rule = rows.getFirst();
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject(
                "select active from security.sharing_rule where tenant_id = ? and id = ?",
                Boolean.class, tenantId, ruleId));

        if (!active) {
            int gone = jdbc.update("""
                    update security.record_share set revoked_at = now()
                    where tenant_id = ? and cause = 'sharing_rule' and cause_ref = ? and revoked_at is null
                    """, tenantId, rule.code());
            complete(tenantId, job.id(), gone, gone, 0, gone, "Rule inactive: materialized shares revoked");
            return new int[]{0, gone};
        }

        List<UUID> grantees = granteesOf(tenantId, rule);
        AuthorizationService.RuleTerm term = authorization.ruleTerm(object, "t", rule);
        if (term == null || grantees.isEmpty()) {
            complete(tenantId, job.id(), 0, 0, 0, 0,
                    grantees.isEmpty() ? "Rule target has no members" : "Rule criteria cannot be evaluated");
            return new int[]{0, 0};
        }

        int total = countMatching(tenantId, object, term);
        int offset = job.processedUnits();
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(term.args());
        args.add(batchSize);
        args.add(offset);
        List<UUID> recordIds = jdbc.query("select t.id from " + object.qualifiedTable() + " t"
                        + " where t.tenant_id = ?"
                        + (object.softDeleted() ? " and t.deleted_at is null" : "")
                        + " and (" + term.sql() + ") order by t.id limit ? offset ?",
                (rs, i) -> rs.getObject(1, UUID.class), args.toArray());

        int written = 0;
        String stamp = job.id().toString();
        for (UUID recordId : recordIds) {
            for (UUID grantee : grantees) {
                written += jdbc.update("""
                        insert into security.record_share
                          (tenant_id, object_type, record_id, grantee_user_id, access_level, cause,
                           cause_ref, cause_detail)
                        values (?, ?, ?, ?, ?, 'sharing_rule', ?, ?)
                        on conflict (tenant_id, object_type, record_id, grantee_user_id, cause,
                                     coalesce(cause_ref, '')) where revoked_at is null
                        do update set access_level = excluded.access_level,
                                      cause_detail = excluded.cause_detail
                        """, tenantId, object.name(), recordId, grantee, rule.accessLevel(),
                        rule.code(), stamp);
            }
        }

        int processed = offset + recordIds.size();
        if (recordIds.size() < batchSize || processed >= total) {
            // Generational sweep: anything this rule wrote in an earlier generation and
            // did not refresh in this one is no longer justified.
            int revoked = jdbc.update("""
                    update security.record_share set revoked_at = now()
                    where tenant_id = ? and cause = 'sharing_rule' and cause_ref = ?
                      and revoked_at is null and coalesce(cause_detail, '') <> ?
                    """, tenantId, rule.code(), stamp);
            jdbc.update("update security.sharing_rule set last_recomputed_at = now() where tenant_id = ? and id = ?",
                    tenantId, ruleId);
            complete(tenantId, job.id(), total, processed, written, revoked,
                    "Materialized " + written + " share(s) for " + grantees.size() + " grantee(s)");
            return new int[]{written, revoked};
        }

        jdbc.update("""
                update security.sharing_recompute_job
                set total_units = ?, processed_units = ?, shares_written = shares_written + ?,
                    message = ?
                where tenant_id = ? and id = ?
                """, total, processed, written,
                "In progress: " + processed + " of " + total + " record(s)", tenantId, job.id());
        return new int[]{written, 0};
    }

    private int[] recomputeRecord(UUID tenantId, SecurableObject object, UUID recordId) {
        List<UUID> ruleIds = jdbc.query("""
                select id from security.sharing_rule
                where tenant_id = ? and object_type = ? and active = true
                """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, object.name());
        int written = 0;
        int revoked = 0;
        for (UUID ruleId : ruleIds) {
            // Re-enqueue the rule rather than trying to patch one record in place: the
            // generational sweep is what keeps materialization honest, and it belongs
            // to the rule-scoped job.
            jdbc.update("""
                    insert into security.sharing_recompute_job
                      (tenant_id, object_type, scope, scope_ref, trigger_reason)
                    values (?, ?, 'RULE', ?, ?)
                    """, tenantId, object.name(), ruleId, "Record " + recordId + " changed");
        }
        return new int[]{written, revoked};
    }

    private int countMatching(UUID tenantId, SecurableObject object,
                              AuthorizationService.RuleTerm term) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(term.args());
        Integer count = jdbc.queryForObject("select count(*) from " + object.qualifiedTable() + " t"
                        + " where t.tenant_id = ?"
                        + (object.softDeleted() ? " and t.deleted_at is null" : "")
                        + " and (" + term.sql() + ")",
                Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    /** Who a rule grants to. */
    List<UUID> granteesOf(UUID tenantId, AuthorizationService.SharingRuleRow rule) {
        return switch (rule.targetType()) {
            case "USER" -> List.of(rule.targetId());
            case "GROUP" -> jdbc.query(
                    "select user_id from security.user_group_member where tenant_id = ? and group_id = ?",
                    (rs, i) -> rs.getObject(1, UUID.class), tenantId, rule.targetId());
            case "TERRITORY" -> jdbc.query(
                    "select user_id from security.territory_member where tenant_id = ? and territory_id = ?",
                    (rs, i) -> rs.getObject(1, UUID.class), tenantId, rule.targetId());
            case "ROLE" -> rule.targetPath() == null ? List.of() : jdbc.query("""
                    select ura.user_id from security.user_role_assignment ura
                    join security.role_node rn on rn.tenant_id = ura.tenant_id and rn.id = ura.role_node_id
                    where ura.tenant_id = ? and (rn.path = ? or (? and rn.path like ?))
                      and (ura.expires_at is null or ura.expires_at > now())
                    """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, rule.targetPath(),
                    rule.targetIncludesSubordinates(), rule.targetPath() + "%");
            default -> List.of();
        };
    }

    private void complete(UUID tenantId, UUID jobId, int total, int processed, int written, int revoked,
                          String message) {
        jdbc.update("""
                update security.sharing_recompute_job
                set status = 'COMPLETE', total_units = ?, processed_units = ?,
                    shares_written = shares_written + ?, shares_revoked = shares_revoked + ?,
                    finished_at = now(), message = ?
                where tenant_id = ? and id = ?
                """, total, processed, written, revoked, message, tenantId, jobId);
    }

    // ------------------------------------------------------------------ groups and territories

    public record NamedRow(UUID id, String code, String name, int memberCount) {}

    @Transactional(readOnly = true)
    public List<NamedRow> userGroups() {
        return jdbc.query("""
                select g.id, g.code, g.name,
                       (select count(*) from security.user_group_member m
                        where m.tenant_id = g.tenant_id and m.group_id = g.id) as member_count
                from security.user_group g where g.tenant_id = ? and g.active = true order by g.code
                """, (rs, i) -> new NamedRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getInt("member_count")),
                TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<NamedRow> territories() {
        return jdbc.query("""
                select t.id, t.code, t.name,
                       (select count(*) from security.territory_member m
                        where m.tenant_id = t.tenant_id and m.territory_id = t.id) as member_count
                from security.territory t where t.tenant_id = ? and t.active = true order by t.code
                """, (rs, i) -> new NamedRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getInt("member_count")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public void addTerritoryMember(UUID territoryId, UUID userId) {
        authorization.requirePermission("SYS.MANAGE_SHARING", "change territory membership");
        jdbc.update("""
                insert into security.territory_member (tenant_id, territory_id, user_id)
                values (?, ?, ?) on conflict do nothing
                """, TenantContext.get().tenantId(), territoryId, userId);
        audit.record("TERRITORY_MEMBER_ADDED", "TERRITORY", territoryId,
                "Added a user to a territory", Map.of("userId", userId.toString()));
        onRoleMembershipChanged(userId);
    }

    @Transactional
    public NamedRow createUserGroup(String code, String name) {
        authorization.requirePermission("SYS.MANAGE_SHARING", "create a user group");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.user_group (id, tenant_id, code, name, created_by)
                values (?, ?, ?, ?, ?)
                """, id, TenantContext.get().tenantId(), code.trim().toUpperCase(Locale.ROOT), name,
                TenantContext.get().userId());
        audit.record("USER_GROUP_CREATED", "USER_GROUP", id, "Created user group " + code, Map.of());
        return userGroups().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void addUserGroupMember(UUID groupId, UUID userId) {
        authorization.requirePermission("SYS.MANAGE_SHARING", "change user group membership");
        jdbc.update("""
                insert into security.user_group_member (tenant_id, group_id, user_id)
                values (?, ?, ?) on conflict do nothing
                """, TenantContext.get().tenantId(), groupId, userId);
        audit.record("USER_GROUP_MEMBER_ADDED", "USER_GROUP", groupId,
                "Added a user to a group", Map.of("userId", userId.toString()));
        onRoleMembershipChanged(userId);
    }

    /** Members of the caller's tenant, for the sharing and explainer screens. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> tenantUsers() {
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.query("""
                select u.id, u.email, u.display_name, u.role, rn.code as role_node, rn.path,
                       p.code as profile
                from identity.app_user u
                left join security.user_role_assignment ura on ura.tenant_id = u.tenant_id and ura.user_id = u.id
                left join security.role_node rn on rn.tenant_id = u.tenant_id and rn.id = ura.role_node_id
                left join security.user_profile up on up.tenant_id = u.tenant_id and up.user_id = u.id
                left join security.profile p on p.tenant_id = u.tenant_id and p.id = up.profile_id
                where u.tenant_id = ? and u.active = true
                order by u.display_name
                """, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getObject("id", UUID.class));
                    row.put("email", rs.getString("email"));
                    row.put("displayName", rs.getString("display_name"));
                    row.put("crmRole", rs.getString("role"));
                    row.put("roleNode", rs.getString("role_node"));
                    row.put("rolePath", rs.getString("path"));
                    row.put("profile", rs.getString("profile"));
                    out.add(row);
                }, TenantContext.get().tenantId());
        return out;
    }
}
