package com.axiom.admin;

import com.axiom.auth.CrmRole;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final PlatformSession platformSession;
    private final com.axiom.identity.StepUpService stepUp;
    private final com.axiom.identity.SessionService sessions;
    private final com.axiom.identity.PasswordPolicyService passwordPolicy;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

    public AdminService(JdbcTemplate jdbc, PlatformSession platformSession,
                        com.axiom.identity.StepUpService stepUp,
                        com.axiom.identity.SessionService sessions,
                        com.axiom.identity.PasswordPolicyService passwordPolicy) {
        this.jdbc = jdbc;
        this.platformSession = platformSession;
        this.stepUp = stepUp;
        this.sessions = sessions;
        this.passwordPolicy = passwordPolicy;
    }

    public record UserRow(UUID id, String tenantSlug, String tenantName, String displayName,
                          String email, String role, boolean active, boolean platformUser) {}
    public record CreateUserRequest(@NotBlank String displayName, @Email @NotBlank String email,
                                    @NotBlank String role, @NotBlank String password) {}
    public record CompanyAccountRow(UUID tenantId, String tenantSlug, String tenantName, String legalName,
                                    String accountStatus, LocalDate trialStartAt, LocalDate trialEndsAt,
                                    int trialExtensionCount, int maxTrialExtensionDays,
                                    String inactiveReason, OffsetDateTime inactiveAt) {}
    public record TrialExtensionRequest(@PositiveOrZero int days, @PositiveOrZero int months, String note) {}
    public record CompanyStatusRequest(@NotBlank String status, String reason) {}
    public record BillingRow(UUID tenantId, String tenantSlug, String tenantName, String planCode,
                             String paymentStatus, String billingEmail, LocalDate currentPeriodStart,
                             LocalDate currentPeriodEnd, String invoiceNumber, BigDecimal amount,
                             String currency, String invoiceStatus, LocalDate dueAt) {}

    @Transactional(readOnly = true)
    public List<UserRow> users() {
        TenantContext.Principal principal = TenantContext.get();
        List<UserRow> tenantUsers = jdbc.query("""
                select u.id, t.slug, t.name as tenant_name, u.display_name, u.email, u.role, u.active
                from identity.app_user u
                join platform.tenant t on t.id = u.tenant_id
                where u.tenant_id = ?
                order by u.display_name
                """, (rs, i) -> new UserRow(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("tenant_name"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getBoolean("active"),
                false
        ), principal.tenantId());

        if (!CrmRole.current(principal.role()).platform()) return tenantUsers;

        List<UserRow> platformUsers = jdbc.query("""
                select id, display_name, email, role, active
                from platform.platform_user
                order by display_name
                """, (rs, i) -> new UserRow(
                rs.getObject("id", UUID.class),
                "platform",
                "Axiom Platform",
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getBoolean("active"),
                true
        ));
        platformUsers.addAll(tenantUsers);
        return platformUsers;
    }

    @Transactional
    public UserRow createUser(CreateUserRequest request) {
        String actorRole = TenantContext.get().role();
        if (!CrmRole.SUPER_ADMIN.name().equals(actorRole) && !CrmRole.TENANT_ADMIN.name().equals(actorRole)) {
            throw new ForbiddenException("User management requires Super Admin or Tenant Admin");
        }
        CrmRole requestedRole = CrmRole.current(request.role().trim().toUpperCase());
        if (requestedRole.platform()) throw new ForbiddenException("Platform roles are managed outside tenant user creation");
        // Granting a role is a controlled action (FR-TEN-009), and an impersonating
        // operator must not be able to grant anything at all (FR-TEN-011).
        com.axiom.identity.ImpersonationService.assertNotImpersonating("Creating a user and granting a role");
        stepUp.requireStepUp("Creating a user with the " + requestedRole.name() + " role");
        // The tenant's own password policy applies to a credential an administrator
        // sets on someone else's behalf, not only to self-service changes.
        passwordPolicy.validate(TenantContext.get().tenantId(), null,
                request.email().trim().toLowerCase(), request.password());

        UUID id = UUID.randomUUID();
        String hash = bcrypt.encode(request.password());
        jdbc.update("""
                insert into identity.app_user(id, tenant_id, email, password_hash, display_name, role, active)
                values (?, ?, ?, ?, ?, ?, true)
                """, id, TenantContext.get().tenantId(), request.email().trim().toLowerCase(),
                hash, request.displayName().trim(), requestedRole.name());
        jdbc.update("""
                insert into identity.password_history(tenant_id, user_id, password_hash) values (?, ?, ?)
                """, TenantContext.get().tenantId(), id, hash);
        return users().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Created user was not found"));
    }

    @Transactional
    public void setUserActive(UUID id, boolean active) {
        String actorRole = TenantContext.get().role();
        if (!CrmRole.SUPER_ADMIN.name().equals(actorRole) && !CrmRole.TENANT_ADMIN.name().equals(actorRole)) {
            throw new ForbiddenException("User management requires Super Admin or Tenant Admin");
        }
        com.axiom.identity.ImpersonationService.assertNotImpersonating("Enabling or disabling a user");
        stepUp.requireStepUp(active ? "Re-enabling a user" : "Disabling a user");
        int updated = jdbc.update("""
                update identity.app_user set active = ?, updated_at = now()
                where tenant_id = ? and id = ?
                """, active, TenantContext.get().tenantId(), id);
        if (updated == 0) throw new NotFoundException("User not found");
        if (!active) {
            // Deactivation without session revocation leaves a live token working
            // until it expires, which makes "disabled" mean "disabled tomorrow".
            sessions.revokeAllForUserSystem(TenantContext.get().tenantId(), id,
                    "Account disabled by an administrator");
        }
    }

    @Transactional(readOnly = true)
    public List<CompanyAccountRow> companies() {
        platformSession.requirePlatformAndGrant();
        return jdbc.query("""
                select t.id as tenant_id, t.slug, t.name as tenant_name, ca.legal_name, ca.account_status,
                       ca.trial_start_at, ca.trial_ends_at, ca.trial_extension_count,
                       ca.max_trial_extension_days, ca.inactive_reason, ca.inactive_at
                from platform.company_account ca
                join platform.tenant t on t.id = ca.tenant_id
                order by t.name
                """, (rs, i) -> new CompanyAccountRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getString("slug"),
                rs.getString("tenant_name"),
                rs.getString("legal_name"),
                rs.getString("account_status"),
                rs.getObject("trial_start_at", LocalDate.class),
                rs.getObject("trial_ends_at", LocalDate.class),
                rs.getInt("trial_extension_count"),
                rs.getInt("max_trial_extension_days"),
                rs.getString("inactive_reason"),
                rs.getObject("inactive_at", OffsetDateTime.class)
        ));
    }

    @Transactional
    public CompanyAccountRow extendTrial(UUID tenantId, TrialExtensionRequest request) {
        platformSession.requirePlatformAndGrant();
        com.axiom.identity.ImpersonationService.assertNotImpersonating("Extending a trial");
        stepUp.requireStepUp("Extending a customer trial");
        int days = request.days() + (request.months() * 30);
        if (days <= 0) throw new ForbiddenException("Trial extension must add at least one day");
        int updated = jdbc.update("""
                update platform.company_account
                set account_status = 'TRIAL',
                    trial_start_at = coalesce(trial_start_at, current_date),
                    trial_ends_at = coalesce(trial_ends_at, current_date) + (? * interval '1 day'),
                    trial_extension_count = trial_extension_count + 1,
                    updated_at = now(),
                    inactive_reason = null,
                    inactive_at = null
                where tenant_id = ?
                  and (trial_extension_count + 1) * ? <= max_trial_extension_days
                """, days, tenantId, days);
        if (updated == 0) throw new NotFoundException("Trial account not found or extension exceeds the configured limit");
        jdbc.update("""
                insert into platform.trial_account_event(tenant_id, action, days_delta, months_delta, actor_id, note)
                values (?, 'EXTENDED', ?, ?, ?, ?)
                """, tenantId, request.days(), request.months(), TenantContext.get().userId(), clean(request.note()));
        return companies().stream().filter(row -> row.tenantId().equals(tenantId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Company account not found"));
    }

    @Transactional
    public CompanyAccountRow setCompanyStatus(UUID tenantId, CompanyStatusRequest request) {
        platformSession.requirePlatformAndGrant();
        com.axiom.identity.ImpersonationService.assertNotImpersonating("Changing a company account status");
        stepUp.requireStepUp("Changing a company account status");
        String status = request.status().trim().toUpperCase();
        if (!List.of("TRIAL", "ACTIVE", "PAST_DUE", "INACTIVE", "SUSPENDED").contains(status)) {
            throw new ForbiddenException("Unsupported company status");
        }
        int updated = jdbc.update("""
                update platform.company_account
                set account_status = ?, inactive_reason = ?,
                    inactive_at = case when ? in ('INACTIVE','SUSPENDED','PAST_DUE') then now() else null end,
                    updated_at = now()
                where tenant_id = ?
                """, status, clean(request.reason()), status, tenantId);
        if (updated == 0) throw new NotFoundException("Company account not found");
        jdbc.update("update platform.tenant set status = ? where id = ?",
                List.of("INACTIVE", "SUSPENDED").contains(status) ? "suspended" : "active", tenantId);
        jdbc.update("""
                insert into platform.trial_account_event(tenant_id, action, actor_id, note)
                values (?, ?, ?, ?)
                """, tenantId, lifecycleAction(status),
                TenantContext.get().userId(), clean(request.reason()));
        return companies().stream().filter(row -> row.tenantId().equals(tenantId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Company account not found"));
    }

    @Transactional(readOnly = true)
    public List<BillingRow> billing() {
        platformSession.requirePlatformAndGrant();
        return jdbc.query("""
                select t.id as tenant_id, t.slug, t.name as tenant_name, ba.plan_code, ba.payment_status,
                       ba.billing_email, ba.current_period_start, ba.current_period_end,
                       i.invoice_number, i.amount, i.currency, i.status as invoice_status, i.due_at
                from billing.billing_account ba
                join platform.tenant t on t.id = ba.tenant_id
                left join lateral (
                  select invoice_number, amount, currency, status, due_at
                  from billing.invoice i where i.tenant_id = ba.tenant_id
                  order by i.created_at desc limit 1
                ) i on true
                order by t.name
                """, (rs, i) -> new BillingRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getString("slug"),
                rs.getString("tenant_name"),
                rs.getString("plan_code"),
                rs.getString("payment_status"),
                rs.getString("billing_email"),
                rs.getObject("current_period_start", LocalDate.class),
                rs.getObject("current_period_end", LocalDate.class),
                rs.getString("invoice_number"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("invoice_status"),
                rs.getObject("due_at", LocalDate.class)
        ));
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String lifecycleAction(String status) {
        return switch (status) {
            case "ACTIVE" -> "ACTIVATED";
            case "INACTIVE", "PAST_DUE" -> "INACTIVATED";
            case "SUSPENDED" -> "SUSPENDED";
            case "TRIAL" -> "CREATED";
            default -> "INACTIVATED";
        };
    }
}
