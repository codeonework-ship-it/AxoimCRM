package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Segregation of duties (FR-SEC-009).
 *
 * <p>Administrators declare pairs of permissions no single user may hold — "can
 * create a vendor" with "can approve vendor payment", "can submit a deal" with "can
 * approve its discount". This service evaluates those declarations at the three
 * points the requirement names, and they are three because one is not enough:
 *
 * <ol>
 *   <li><b>On grant</b> — blocks the grant, naming the conflict and the existing
 *       grant that causes it. A message that says only "conflict" leaves the
 *       administrator to hunt for which of a user's five permission sets is the
 *       other half.</li>
 *   <li><b>On profile change</b> — the same check, because moving a user to a wider
 *       profile is a grant by another name.</li>
 *   <li><b>On a scheduled sweep</b> — a conflict declared today may already be
 *       violated by grants made last year. Those must surface as findings to
 *       remediate. Grandfathering them in silently is how a control becomes
 *       decoration.</li>
 * </ol>
 */
@Service
public class SodService {

    private static final Logger log = LoggerFactory.getLogger(SodService.class);

    private final JdbcTemplate jdbc;
    private final PermissionResolver resolver;
    private final AuthorizationService authorization;
    private final AuditService audit;
    private final SystemTaskRunner tasks;

    public SodService(JdbcTemplate jdbc, PermissionResolver resolver,
                      AuthorizationService authorization, AuditService audit,
                      SystemTaskRunner tasks) {
        this.jdbc = jdbc;
        this.resolver = resolver;
        this.authorization = authorization;
        this.audit = audit;
        this.tasks = tasks;
    }

    public record Conflict(UUID id, String code, String name, String permissionA, String permissionB,
                           String severity, String rationale, boolean active) {}

    public record ConflictRequest(@NotBlank String code, @NotBlank String name,
                                  @NotBlank String permissionA, @NotBlank String permissionB,
                                  String severity, String rationale) {}

    public record Finding(UUID id, UUID conflictId, String conflictCode, String conflictName,
                          UUID userId, String userEmail, String holderA, String holderB,
                          String status, Instant detectedAt) {}

    // ------------------------------------------------------------------ declarations

    @Transactional(readOnly = true)
    public List<Conflict> conflicts() {
        return jdbc.query("""
                select id, code, name, permission_a, permission_b, severity, rationale, active
                from security.sod_conflict where tenant_id = ? order by code
                """, (rs, i) -> new Conflict(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("permission_a"), rs.getString("permission_b"),
                        rs.getString("severity"), rs.getString("rationale"), rs.getBoolean("active")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public Conflict declare(ConflictRequest request) {
        authorization.requirePermission("SYS.MANAGE_SOD", "declare a segregation-of-duties conflict");
        UUID tenantId = TenantContext.get().tenantId();
        // The pair is unordered; normalizing here means (A,B) and (B,A) are the same
        // declaration rather than two rules that both fire on every violation.
        String a = request.permissionA().trim().toUpperCase(Locale.ROOT);
        String b = request.permissionB().trim().toUpperCase(Locale.ROOT);
        if (a.equals(b)) {
            throw new ConflictException("A permission cannot conflict with itself: " + a);
        }
        String low = a.compareTo(b) < 0 ? a : b;
        String high = a.compareTo(b) < 0 ? b : a;

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into security.sod_conflict
                  (id, tenant_id, code, name, permission_a, permission_b, severity, rationale, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, request.code().trim().toUpperCase(Locale.ROOT), request.name(),
                low, high, request.severity() == null ? "HIGH" : request.severity(),
                request.rationale(), TenantContext.get().userId());

        audit.record("SOD_CONFLICT_DECLARED", "SOD_CONFLICT", id,
                "Declared conflict " + request.code(),
                Map.of("permissionA", low, "permissionB", high,
                        "severity", request.severity() == null ? "HIGH" : request.severity()));

        // A newly declared conflict is swept immediately for the tenant that declared
        // it, so pre-existing violations appear on the screen that created the rule
        // rather than at the next nightly run.
        int found = sweepTenant(tenantId);
        log.info("SoD conflict {} declared; immediate sweep produced {} finding(s)", request.code(), found);

        return new Conflict(id, request.code().trim().toUpperCase(Locale.ROOT), request.name(),
                low, high, request.severity() == null ? "HIGH" : request.severity(),
                request.rationale(), true);
    }

    @Transactional
    public void retire(UUID conflictId) {
        authorization.requirePermission("SYS.MANAGE_SOD", "retire a segregation-of-duties conflict");
        jdbc.update("update security.sod_conflict set active = false where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), conflictId);
        audit.record("SOD_CONFLICT_RETIRED", "SOD_CONFLICT", conflictId,
                "Retired a segregation-of-duties conflict", Map.of());
    }

    // ------------------------------------------------------------------ evaluation

    /**
     * Point 1 and 2: block a grant that would complete a conflict.
     *
     * @param targetUserId the user receiving the grant
     * @param incomingCodes the permission codes the grant would add
     * @throws ConflictException naming the conflict and the permission already held
     */
    @Transactional(readOnly = true)
    public void assertGrantIsPermitted(UUID targetUserId, Set<String> incomingCodes) {
        if (incomingCodes.isEmpty()) return;
        AccessContext held = resolver.forUser(targetUserId);
        List<Conflict> active = conflicts().stream().filter(Conflict::active).toList();
        for (Conflict conflict : active) {
            String other = null;
            String incoming = null;
            if (incomingCodes.contains(conflict.permissionA()) && held.holds(conflict.permissionB())) {
                incoming = conflict.permissionA();
                other = conflict.permissionB();
            } else if (incomingCodes.contains(conflict.permissionB()) && held.holds(conflict.permissionA())) {
                incoming = conflict.permissionB();
                other = conflict.permissionA();
            }
            if (other != null) {
                String source = grantSource(held, other);
                throw new ConflictException("Grant blocked by segregation-of-duties conflict "
                        + conflict.code() + " (" + conflict.name() + "): this grant would add "
                        + incoming + ", and the user already holds " + other + " through " + source
                        + ". Remove " + other + " first, or grant " + incoming + " to a different user.");
            }
        }
        // Both halves arriving in one grant is the same violation, caught here rather
        // than left for the sweep to find after the fact.
        for (Conflict conflict : active) {
            if (incomingCodes.contains(conflict.permissionA()) && incomingCodes.contains(conflict.permissionB())) {
                throw new ConflictException("Grant blocked by segregation-of-duties conflict "
                        + conflict.code() + " (" + conflict.name() + "): a single grant cannot convey both "
                        + conflict.permissionA() + " and " + conflict.permissionB() + ".");
            }
        }
    }

    /** Human-readable origin of a held permission, for the blocking message. */
    private String grantSource(AccessContext held, String code) {
        List<String> sources = jdbc.query("""
                select case hp.holder_type when 'PROFILE' then 'profile ' || p.code
                                           else 'permission set ' || ps.code end as source
                from security.holder_permission hp
                left join security.profile p on p.tenant_id = hp.tenant_id and p.id = hp.holder_id
                left join security.permission_set ps on ps.tenant_id = hp.tenant_id and ps.id = hp.holder_id
                where hp.tenant_id = ? and hp.permission_code = ?
                  and (hp.holder_id = ? or hp.holder_id in (%s))
                """.formatted(placeholders(held.permissionSetIds().size())),
                (rs, i) -> rs.getString("source"), args(held, code));
        if (!sources.isEmpty()) return String.join(" and ", new LinkedHashSet<>(sources));
        // Object-derived codes come from object_permission rather than holder_permission.
        return "profile " + held.profileCode();
    }

    private Object[] args(AccessContext held, String code) {
        List<Object> args = new ArrayList<>();
        args.add(held.tenantId());
        args.add(code);
        args.add(held.profileId());
        if (held.permissionSetIds().isEmpty()) args.add(null);
        else args.addAll(held.permissionSetIds());
        return args.toArray();
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(Math.max(count, 1), "?"));
    }

    // ------------------------------------------------------------------ sweep

    @Transactional(readOnly = true)
    public List<Finding> findings() {
        return jdbc.query("""
                select f.id, f.conflict_id, c.code, c.name, f.user_id, u.email,
                       f.holder_a, f.holder_b, f.status, f.detected_at
                from security.sod_finding f
                join security.sod_conflict c on c.tenant_id = f.tenant_id and c.id = f.conflict_id
                join identity.app_user u on u.tenant_id = f.tenant_id and u.id = f.user_id
                where f.tenant_id = ?
                order by f.detected_at desc
                """, (rs, i) -> new Finding(rs.getObject("id", UUID.class),
                        rs.getObject("conflict_id", UUID.class), rs.getString("code"), rs.getString("name"),
                        rs.getObject("user_id", UUID.class), rs.getString("email"),
                        rs.getString("holder_a"), rs.getString("holder_b"), rs.getString("status"),
                        rs.getTimestamp("detected_at").toInstant()),
                TenantContext.get().tenantId());
    }

    /** On-demand sweep of the caller's tenant. */
    @Transactional
    public int sweep() {
        authorization.requirePermission("SYS.MANAGE_SOD", "run a segregation-of-duties sweep");
        int found = sweepTenant(TenantContext.get().tenantId());
        audit.record("SOD_SWEEP", "SOD_CONFLICT", null,
                "Segregation-of-duties sweep recorded " + found + " open finding(s)",
                Map.of("openFindings", found));
        return found;
    }

    int sweepTenant(UUID tenantId) {
        List<Conflict> active = jdbc.query("""
                select id, code, name, permission_a, permission_b, severity, rationale, active
                from security.sod_conflict where tenant_id = ? and active = true
                """, (rs, i) -> new Conflict(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("permission_a"), rs.getString("permission_b"),
                        rs.getString("severity"), rs.getString("rationale"), true), tenantId);
        if (active.isEmpty()) return 0;

        List<UUID> users = jdbc.query("select id from identity.app_user where tenant_id = ? and active = true",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId);

        int open = 0;
        for (UUID userId : users) {
            AccessContext ctx = resolver.forUser(userId);
            for (Conflict conflict : active) {
                boolean violated = ctx.holds(conflict.permissionA()) && ctx.holds(conflict.permissionB());
                if (violated) {
                    open++;
                    // uq_sod_finding_open keeps one OPEN row per (conflict, user), so a
                    // nightly sweep reports a standing violation once rather than
                    // producing a new finding every night until somebody fixes it.
                    jdbc.update("""
                            insert into security.sod_finding
                              (tenant_id, conflict_id, user_id, holder_a, holder_b, status)
                            values (?, ?, ?, ?, ?, 'OPEN')
                            on conflict (tenant_id, conflict_id, user_id) where status = 'OPEN'
                            do nothing
                            """, tenantId, conflict.id(), userId,
                            grantSource(ctx, conflict.permissionA()), grantSource(ctx, conflict.permissionB()));
                } else {
                    jdbc.update("""
                            update security.sod_finding
                            set status = 'REMEDIATED', resolved_at = now()
                            where tenant_id = ? and conflict_id = ? and user_id = ? and status = 'OPEN'
                            """, tenantId, conflict.id(), userId);
                }
            }
        }
        return open;
    }

    /**
     * The third evaluation point: a sweep across every tenant, on a schedule.
     *
     * <p>It exists because a conflict declared today may already be violated by
     * grants made last year, and those violations have to become findings rather
     * than be grandfathered in. Tenant binding and per-tenant transactions come from
     * {@link SystemTaskRunner}.
     */
    @Scheduled(cron = "${axiom.security.sod-sweep-cron:0 15 2 * * *}")
    public void scheduledSweep() {
        int open = tasks.forEachTenant("Segregation-of-duties sweep", tenantId -> {
            int found = sweepTenant(tenantId);
            if (found > 0) {
                log.warn("Segregation-of-duties sweep: tenant {} has {} open finding(s)", tenantId, found);
            }
            return found;
        });
        log.info("Segregation-of-duties sweep complete: {} open finding(s) across all tenants", open);
    }

    @Transactional
    public void resolveFinding(UUID findingId, String status, String note) {
        authorization.requirePermission("SYS.MANAGE_SOD", "resolve a segregation-of-duties finding");
        jdbc.update("""
                update security.sod_finding
                set status = ?, resolved_at = now(), resolved_by = ?, resolution_note = ?
                where tenant_id = ? and id = ?
                """, status, TenantContext.get().userId(), note,
                TenantContext.get().tenantId(), findingId);
        audit.record("SOD_FINDING_RESOLVED", "SOD_FINDING", findingId,
                "Finding marked " + status, Map.of("status", status, "note", note == null ? "" : note));
    }
}
