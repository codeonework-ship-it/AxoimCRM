package com.axiom.identity;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant provisioning and lifecycle (FR-TEN-001, FR-TEN-002).
 *
 * <p><b>Rollback is the transaction, not a compensating action.</b> Provisioning
 * writes a tenant row, its configuration baseline, its entitlement and billing
 * records, its reference data, its pipeline and its first administrator inside one
 * transaction. If any step fails, PostgreSQL removes all of it — there is no
 * half-provisioned tenant to clean up, which is what FR-TEN-001's on-failure
 * clause demands. Hand-rolled compensation logic would be the wrong answer here:
 * it is the thing that breaks when the failure is the one you did not anticipate.
 *
 * <p><b>Idempotency is a ledger, not a guess.</b>
 * {@code platform.tenant_provisioning_request} has the request key as its primary
 * key, so a retry with the same key returns the original tenant instead of
 * creating a second one.
 *
 * <p><b>Transitions are a whitelist.</b> Anything not in {@link #PERMITTED} is
 * refused, naming what is and is not allowed from the current state.
 */
@Service
public class TenantLifecycleService {

    /** Permitted state transitions (FR-TEN-002). */
    static final Map<String, Set<String>> PERMITTED = Map.of(
            "provisioning", Set.of("active", "terminating"),
            "active", Set.of("suspended", "terminating"),
            // A suspended tenant can be reinstated, or moved on to termination.
            "suspended", Set.of("active", "terminating"),
            // Termination is reversible right up to data destruction — that window
            // is the whole point of the retention period.
            "terminating", Set.of("terminated", "active"),
            "terminated", Set.of());

    /** States in which business writes are refused (FR-TEN-002). */
    private static final Set<String> WRITE_BLOCKED = Set.of("suspended", "terminating", "terminated");

    private static final int DEFAULT_RETENTION_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final PlatformSession platformSession;
    private final PasswordPolicyService passwordPolicy;
    private final AuditService audit;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public TenantLifecycleService(JdbcTemplate jdbc, PlatformSession platformSession,
                                  PasswordPolicyService passwordPolicy, AuditService audit) {
        this.jdbc = jdbc;
        this.platformSession = platformSession;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
    }

    public record ProvisionRequest(String requestKey, String slug, String name, String legalName,
                                   String adminEmail, String adminDisplayName, String adminPassword,
                                   String planCode) {}

    public record ProvisionResult(UUID tenantId, String slug, String status, UUID adminUserId,
                                  boolean created, List<String> baseline, String note) {}

    public record TenantRow(UUID id, String slug, String name, String status, boolean impersonationConsent,
                            java.time.Instant terminatingAt, java.time.Instant terminatedAt,
                            LocalDate retentionUntil) {}

    /** Whether the lifecycle state of a tenant blocks business writes. */
    public static boolean blocksBusinessWrites(String status) {
        return status != null && WRITE_BLOCKED.contains(status);
    }

    // ------------------------------------------------------------------
    // Provisioning
    // ------------------------------------------------------------------

    @Transactional
    public ProvisionResult provision(ProvisionRequest request) {
        TenantContext.Principal operator = TenantContext.get();
        CrmRole.requirePlatform(operator.role());
        if (CrmRole.current(operator.role()).readOnly()) {
            throw new ForbiddenException("A read-only platform role cannot provision a tenant");
        }
        ImpersonationService.assertNotImpersonating("Provisioning a tenant");
        platformSession.requirePlatformAndGrant();

        String requestKey = required(request.requestKey(),
                "Send a provisioning request key so a retry cannot create a second tenant.");
        String slug = required(request.slug(), "A workspace slug is required.").toLowerCase();
        if (!slug.matches("^[a-z][a-z0-9-]{2,40}$")) {
            throw new IllegalArgumentException("The workspace slug must be 3-41 characters, start with a letter, "
                    + "and contain only lower-case letters, digits and hyphens.");
        }
        String name = required(request.name(), "A workspace display name is required.");
        String adminEmail = required(request.adminEmail(), "An initial administrator email is required.")
                .toLowerCase();
        String adminName = required(request.adminDisplayName(), "An initial administrator name is required.");
        String adminPassword = required(request.adminPassword(),
                "An initial administrator password is required.");

        // Idempotency check first: a retry must not even attempt the inserts.
        UUID existing = existingTenantForKey(requestKey);
        if (existing != null) {
            TenantRow row = get(existing);
            return new ProvisionResult(row.id(), row.slug(), row.status(), adminUserId(row.id()), false,
                    List.of(), "This request key was already fulfilled; the existing workspace is returned "
                    + "unchanged. Provisioning is idempotent by request key.");
        }
        if (!jdbc.queryForList("select 1 from platform.tenant where lower(slug) = ?", Integer.class, slug)
                .isEmpty()) {
            throw new ConflictException("The workspace slug \"" + slug + "\" is already taken. "
                    + "Choose another — a tenant slug is immutable once assigned.");
        }

        UUID tenantId = UUID.randomUUID();
        jdbc.update("""
                insert into platform.tenant(id, slug, name, status) values (?, ?, ?, 'provisioning')
                """, tenantId, slug, name);

        // Everything below writes tenant-scoped rows, so the RLS session variable
        // must point at the NEW tenant, not at the operator's current workspace.
        // SET LOCAL: it reverts when this transaction ends.
        bind(tenantId);

        List<String> baseline = new java.util.ArrayList<>();

        jdbc.update("insert into identity.password_policy(tenant_id) values (?)", tenantId);
        baseline.add("password policy");
        jdbc.update("insert into identity.session_policy(tenant_id) values (?)", tenantId);
        baseline.add("session policy");
        jdbc.update("insert into identity.mfa_policy(tenant_id, target_role, required) values (?, '*', false)",
                tenantId);
        baseline.add("multi-factor policy");
        jdbc.update("""
                insert into platform.tenant_branding(tenant_id, primary_colour, sign_in_message)
                values (?, '#0b5fbe', 'Authorized use only. Sign-in activity is recorded.')
                """, tenantId);
        baseline.add("login branding");

        String plan = request.planCode() == null || request.planCode().isBlank()
                ? "TRIAL" : request.planCode().trim().toUpperCase();
        if (!List.of("TRIAL", "STARTER", "PROFESSIONAL", "ENTERPRISE").contains(plan)) {
            throw new IllegalArgumentException("Plan code must be TRIAL, STARTER, PROFESSIONAL or ENTERPRISE");
        }
        jdbc.update("""
                insert into platform.company_account
                  (tenant_id, legal_name, account_status, trial_start_at, trial_ends_at)
                values (?, ?, ?, ?, ?)
                """, tenantId,
                request.legalName() == null || request.legalName().isBlank() ? name : request.legalName().trim(),
                "TRIAL".equals(plan) ? "TRIAL" : "ACTIVE",
                LocalDate.now(),
                "TRIAL".equals(plan) ? LocalDate.now().plusDays(30) : null);
        jdbc.update("""
                insert into billing.billing_account
                  (tenant_id, plan_code, billing_email, payment_status, current_period_start, current_period_end)
                values (?, ?, ?, ?, ?, ?)
                """, tenantId, plan, adminEmail, "TRIAL".equals(plan) ? "TRIAL" : "CURRENT",
                LocalDate.now(), LocalDate.now().plusDays(30));
        baseline.add("entitlement and billing account (" + plan + ")");

        // Composition rules apply to the very first credential too. A tenant whose
        // administrator password was never checked against the policy is a tenant
        // whose policy is decorative.
        passwordPolicy.validate(tenantId, null, adminEmail, adminPassword);
        // validate() rebinds app.tenant_id to the same tenant, but be explicit.
        bind(tenantId);
        UUID adminId = UUID.randomUUID();
        String adminHash = bcrypt.encode(adminPassword);
        jdbc.update("""
                insert into identity.app_user
                  (id, tenant_id, email, password_hash, display_name, role, active)
                values (?, ?, ?, ?, ?, 'TENANT_ADMIN', true)
                """, adminId, tenantId, adminEmail, adminHash, adminName);
        jdbc.update("""
                insert into identity.password_history(tenant_id, user_id, password_hash) values (?, ?, ?)
                """, tenantId, adminId, adminHash);
        baseline.add("initial administrator " + adminEmail);

        seedPipeline(tenantId);
        baseline.add("default pipeline stages");
        seedReferenceData(tenantId);
        baseline.add("governed reference data");

        jdbc.update("update platform.tenant set status = 'active' where id = ?", tenantId);

        try {
            jdbc.update("""
                    insert into platform.tenant_provisioning_request
                      (request_key, tenant_id, requested_by, status, detail)
                    values (?, ?, ?, 'COMPLETED', ?)
                    """, requestKey, tenantId, operator.userId(),
                    "Provisioned workspace " + slug);
        } catch (DuplicateKeyException e) {
            // A concurrent retry won the race. Rolling back is correct: the winner's
            // tenant is the one that exists.
            throw new ConflictException("Another request with the key \"" + requestKey
                    + "\" is already provisioning this workspace. Retry the read to get its result.");
        }

        // Audit belongs to the operator's own workspace, so restore their binding
        // before writing it — the RLS check on audit_event is on tenant_id.
        bind(operator.tenantId());
        audit.record("TENANT_PROVISION", "TENANT", tenantId,
                "Workspace provisioned: " + name + " (" + slug + ")",
                Map.of("slug", slug, "requestKey", requestKey, "plan", plan,
                        "adminEmail", adminEmail, "baseline", baseline));
        return new ProvisionResult(tenantId, slug, "active", adminId, true, baseline,
                "The workspace is active and its administrator can sign in. Retrying with the same request "
                        + "key returns this same workspace rather than creating another.");
    }

    // ------------------------------------------------------------------
    // Lifecycle transitions
    // ------------------------------------------------------------------

    @Transactional
    public TenantRow transition(UUID tenantId, String targetStatus, String reason, boolean confirmed,
                                Integer retentionDays) {
        TenantContext.Principal operator = TenantContext.get();
        CrmRole.requirePlatform(operator.role());
        if (CrmRole.current(operator.role()).readOnly()) {
            throw new ForbiddenException("A read-only platform role cannot change a tenant's lifecycle state");
        }
        ImpersonationService.assertNotImpersonating("Changing a workspace lifecycle state");
        String target = targetStatus == null ? "" : targetStatus.trim().toLowerCase();
        TenantRow current = get(tenantId);
        Set<String> allowed = PERMITTED.getOrDefault(current.status(), Set.of());
        if (!allowed.contains(target)) {
            throw new ConflictException("A workspace in the \"" + current.status() + "\" state cannot move to \""
                    + target + "\". " + (allowed.isEmpty()
                    ? "No further transitions are permitted from this state."
                    : "Permitted next states are: " + String.join(", ", allowed.stream().sorted().toList()) + "."));
        }
        String cleanedReason = required(reason, "Give a reason for this lifecycle change — it is audited.");
        if (List.of("terminating", "terminated").contains(target) && !confirmed) {
            throw new ConflictException("Termination needs explicit confirmation. Resend with "
                    + "\"confirmed\": true once you are certain — a retention period starts immediately "
                    + "and data destruction follows it.");
        }
        int retention = retentionDays == null || retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
        switch (target) {
            case "terminating" -> jdbc.update("""
                    update platform.tenant
                    set status = 'terminating', terminating_at = now(), retention_until = ?, terminated_at = null
                    where id = ?
                    """, LocalDate.now().plusDays(retention), tenantId);
            case "terminated" -> {
                if (current.retentionUntil() != null && current.retentionUntil().isAfter(LocalDate.now())) {
                    throw new ConflictException("The retention period for this workspace runs until "
                            + current.retentionUntil() + ". Data cannot be destroyed before then.");
                }
                jdbc.update("update platform.tenant set status = 'terminated', terminated_at = now() where id = ?",
                        tenantId);
            }
            case "active" -> jdbc.update("""
                    update platform.tenant
                    set status = 'active', terminating_at = null, terminated_at = null, retention_until = null
                    where id = ?
                    """, tenantId);
            default -> jdbc.update("update platform.tenant set status = ? where id = ?", target, tenantId);
        }
        audit.recordWithReason("TENANT_LIFECYCLE", "TENANT", tenantId,
                "Workspace " + current.slug() + " moved from " + current.status() + " to " + target,
                cleanedReason, Map.of("from", current.status(), "to", target,
                        "retentionDays", "terminating".equals(target) ? retention : 0));
        return get(tenantId);
    }

    @Transactional(readOnly = true)
    public List<TenantRow> list() {
        CrmRole.requirePlatform(TenantContext.get().role());
        return jdbc.query("""
                select id, slug, name, status, impersonation_consent, terminating_at, terminated_at, retention_until
                from platform.tenant order by name
                """, (rs, i) -> mapTenant(rs));
    }

    @Transactional(readOnly = true)
    public TenantRow get(UUID tenantId) {
        try {
            return jdbc.queryForObject("""
                    select id, slug, name, status, impersonation_consent, terminating_at, terminated_at,
                           retention_until
                    from platform.tenant where id = ?
                    """, (rs, i) -> mapTenant(rs), tenantId);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("No such workspace");
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static TenantRow mapTenant(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TenantRow(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                rs.getString("status"), rs.getBoolean("impersonation_consent"),
                rs.getTimestamp("terminating_at") == null ? null : rs.getTimestamp("terminating_at").toInstant(),
                rs.getTimestamp("terminated_at") == null ? null : rs.getTimestamp("terminated_at").toInstant(),
                rs.getObject("retention_until", LocalDate.class));
    }

    private UUID existingTenantForKey(String requestKey) {
        try {
            return jdbc.queryForObject("""
                    select tenant_id from platform.tenant_provisioning_request
                    where request_key = ? and status = 'COMPLETED'
                    """, UUID.class, requestKey);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private UUID adminUserId(UUID tenantId) {
        bind(tenantId);
        List<UUID> ids = jdbc.queryForList("""
                select id from identity.app_user
                where tenant_id = ? and role = 'TENANT_ADMIN' order by created_at limit 1
                """, UUID.class, tenantId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Seeds the tenant's default pipeline and its stages.
     *
     * <p>The pipeline row comes first and deliberately mirrors what V60 created
     * for tenants that already existed — same api_name, same name, is_default —
     * so a tenant provisioned today is indistinguishable from one migrated then.
     * V60 also made {@code pipeline_stage.pipeline_id} NOT NULL, which is why
     * every stage insert below carries it: stages are owned by a pipeline, and a
     * stage with no pipeline is not a valid record in any tenant.
     *
     * <p>Probability and forecast category are set rather than left to their
     * column defaults. A pipeline where every stage forecasts at 0% and sits in
     * PIPELINE gives a new tenant a forecast that is technically valid and
     * practically useless, which is worse than an obviously empty one.
     */
    private void seedPipeline(UUID tenantId) {
        UUID pipelineId = UUID.randomUUID();
        jdbc.update("""
                insert into pipeline.pipeline (id, tenant_id, api_name, name, description, is_default)
                values (?, ?, 'default_pipeline', 'Default Pipeline',
                        'Standard B2B sales pipeline created when the workspace was provisioned.', true)
                """, pipelineId, tenantId);

        Object[][] stages = {
                // name, sort, closed, won, needs economic buyer, probability, forecast category
                {"Qualifying", 1, false, false, false, 10, "PIPELINE"},
                {"Proposal", 2, false, false, false, 30, "PIPELINE"},
                {"Negotiation", 3, false, false, true, 60, "BEST_CASE"},
                {"Commit", 4, false, false, true, 85, "COMMIT"},
                {"Closed Won", 5, true, true, false, 100, "CLOSED"},
                {"Closed Lost", 6, true, false, false, 0, "OMITTED"},
        };
        for (Object[] stage : stages) {
            jdbc.update("""
                    insert into crm.pipeline_stage
                      (tenant_id, pipeline_id, name, sort_order, is_closed, is_won,
                       requires_economic_buyer, probability, forecast_category)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, pipelineId, stage[0], stage[1], stage[2], stage[3],
                    stage[4], stage[5], stage[6]);
        }
    }

    /**
     * Mirrors the governed value sets V6 seeded for existing tenants. Held in code
     * rather than copied from a template tenant on purpose: reading another
     * tenant's rows to build a new one is exactly the cross-tenant query ADR-001
     * exists to prevent, and RLS would refuse it anyway.
     */
    private void seedReferenceData(UUID tenantId) {
        Map<String, String[]> sets = new LinkedHashMap<>();
        sets.put("lead_status", new String[]{"Lead status", "CRM", "Governed lead lifecycle statuses"});
        sets.put("pipeline_stage_lifecycle",
                new String[]{"Pipeline stage lifecycle", "SALES", "Open, won and lost stage classifications"});
        sets.put("master_delete_reason",
                new String[]{"Master delete reason", "GOVERNANCE", "Governed reasons for master-data retirement"});
        Map<String, Object[][]> entries = new LinkedHashMap<>();
        entries.put("lead_status", new Object[][]{
                {"NEW", "New", 10}, {"QUALIFIED", "Qualified", 20},
                {"CONVERTED", "Converted", 30}, {"DISQUALIFIED", "Disqualified", 40}});
        entries.put("pipeline_stage_lifecycle", new Object[][]{
                {"OPEN", "Open", 10}, {"WON", "Won", 20}, {"LOST", "Lost", 30}});
        entries.put("master_delete_reason", new Object[][]{
                {"DUPLICATE", "Duplicate", 10}, {"OBSOLETE", "Obsolete", 20},
                {"DATA_QUALITY", "Data quality correction", 30}});
        sets.forEach((apiName, meta) -> {
            UUID setId = UUID.randomUUID();
            jdbc.update("""
                    insert into reference.value_set(id, tenant_id, api_name, label, module, description)
                    values (?, ?, ?, ?, ?, ?)
                    """, setId, tenantId, apiName, meta[0], meta[1], meta[2]);
            for (Object[] entry : entries.get(apiName)) {
                jdbc.update("""
                        insert into reference.value_set_entry
                          (tenant_id, value_set_id, code, label, sort_order, system_managed)
                        values (?, ?, ?, ?, ?, true)
                        """, tenantId, setId, entry[0], entry[1], entry[2]);
            }
        });
    }

    private void bind(UUID tenantId) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
    }

    private static String required(String value, String message) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) throw new IllegalArgumentException(message);
        return cleaned;
    }
}
