package com.axiom.access;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.identity.TenantLifecycleService;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Turns an approved trial request into a real, evaluable workspace.
 *
 * <h2>What this class does not do</h2>
 * It does not provision anything itself. {@link TenantLifecycleService#provision}
 * owns tenant creation, its idempotency ledger and its baseline, and is called
 * here rather than reimplemented — a second provisioning path is a second set of
 * lifecycle bugs, and the two would drift apart the first time either changed.
 *
 * <h2>Idempotency (FR-TEN-001)</h2>
 * Two layers, because either alone has a hole. The request key handed to
 * {@code provision()} is derived from the trial reference, so a retry returns the
 * original tenant from {@code platform.tenant_provisioning_request} instead of
 * building a second one. And a request already in {@code PROVISIONED} short-
 * circuits before any write, so re-approving is a read.
 *
 * <h2>Rollback (FR-TEN-001)</h2>
 * Everything here runs in ONE transaction: the tenant and its baseline, the
 * auditor account, the demo data, the trial window, the activation links and the
 * status change on the request. A failure at any point leaves no tenant, no
 * users and no partial rows, because PostgreSQL removes them — not because
 * compensating code remembered to. The invariant check on role counts is inside
 * that transaction on purpose: if it ever fails, it takes the half-built
 * workspace down with it.
 *
 * <h2>Two accounts, no passwords in email</h2>
 * Every provisioned workspace gets exactly one {@code TENANT_ADMIN} and exactly
 * one {@code AUDITOR} — the second so the trial can be evaluated with the
 * read-only separation a real deployment would use, without the evaluator having
 * to create it. Neither is emailed a password. Each gets a single-use activation
 * link whose token is stored only as a SHA-256 hash, so this table leaking does
 * not hand anyone an account.
 */
@Service
public class TrialProvisioningService {

    /** How long an activation link stays usable. */
    private static final int ACTIVATION_VALID_DAYS = 14;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnpqrstuvwxyz".toCharArray();
    private static final char[] DIGIT = "23456789".toCharArray();
    private static final char[] SYMBOL = "!@#$%^&*()-_=+[]{}?".toCharArray();

    private final JdbcTemplate jdbc;
    private final PlatformSession platformSession;
    private final TenantLifecycleService lifecycle;
    private final TrialRequestService requests;
    private final AuditService audit;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final String publicBaseUrl;

    public TrialProvisioningService(JdbcTemplate jdbc, PlatformSession platformSession,
                                    TenantLifecycleService lifecycle, TrialRequestService requests,
                                    AuditService audit,
                                    @Value("${axiom.public-base-url:http://localhost:5173}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.platformSession = platformSession;
        this.lifecycle = lifecycle;
        this.requests = requests;
        this.audit = audit;
        this.publicBaseUrl = publicBaseUrl;
    }

    public record ApprovalRequest(String slug, String note) {}

    public record ActivationLink(String email, String displayName, String role, String url,
                                 Instant expiresAt) {}

    public record ApprovalResult(UUID trialRequestId, String reference, UUID tenantId, String slug,
                                 String companyName, int trialDays, LocalDate trialStartAt,
                                 LocalDate trialEndsAt, boolean created, int tenantAdminCount,
                                 int auditorCount, List<ActivationLink> activationLinks,
                                 List<String> demoData, String note) {}

    // ------------------------------------------------------------------

    @Transactional
    public ApprovalResult approve(UUID trialRequestId, ApprovalRequest request) {
        platformSession.requirePlatformAndGrant();
        TrialRequestService.requireWritablePlatformRole("Approving a trial request");
        TenantContext.Principal operator = TenantContext.get();

        TrialRequestService.TrialRequestRow row = requests.load(trialRequestId);
        if ("REJECTED".equals(row.status()) || "EXPIRED".equals(row.status())) {
            throw new ConflictException("Trial request " + row.reference() + " is " + row.status()
                    + " and cannot be approved. Ask the requester to submit again if the decision has changed.");
        }
        if ("PROVISIONED".equals(row.status()) && row.provisionedTenantId() != null) {
            // Idempotent by design: re-approving reads, it does not rebuild.
            Map<String, Integer> roles = roleCounts(row.provisionedTenantId());
            LocalDate[] window = trialWindow(row.provisionedTenantId());
            bind(operator.tenantId());
            return new ApprovalResult(row.id(), row.reference(), row.provisionedTenantId(),
                    row.provisionedSlug(), row.companyName(), row.trialDays(), window[0], window[1], false,
                    roles.getOrDefault("TENANT_ADMIN", 0), roles.getOrDefault("AUDITOR", 0),
                    List.of(), List.of(),
                    "This request was already provisioned. The existing workspace is returned unchanged and "
                            + "no new activation links were issued — reissue them from the workspace if they "
                            + "were lost.");
        }

        String slug = uniqueSlug(request != null && request.slug() != null && !request.slug().isBlank()
                ? request.slug() : row.companyName());
        String requestKey = "trial-request:" + row.reference();
        String adminPassword = generatedPassword();

        TenantLifecycleService.ProvisionResult provisioned = lifecycle.provision(
                new TenantLifecycleService.ProvisionRequest(requestKey, slug, row.companyName(),
                        row.companyName(), row.workEmail(), row.fullName(), adminPassword, "TRIAL"));

        UUID tenantId = provisioned.tenantId();
        UUID adminUserId = provisioned.adminUserId();
        bind(tenantId);

        // The auditor account. Created here rather than inside provision() because
        // "every trial gets a read-only reviewer" is a trial policy, not a tenancy
        // rule — a paid enterprise tenant provisions its own roles.
        UUID auditorId = ensureAuditor(tenantId, row);

        List<String> demoData = seedDemoData(tenantId, adminUserId, row.companyName());

        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(row.trialDays());
        jdbc.update("""
                update platform.company_account
                set account_status = 'TRIAL', trial_start_at = ?, trial_ends_at = ?, updated_at = now()
                where tenant_id = ?
                """, start, end, tenantId);
        jdbc.update("""
                update billing.billing_account
                set current_period_start = ?, current_period_end = ?
                where tenant_id = ?
                """, start, end, tenantId);
        jdbc.update("""
                insert into platform.trial_account_event(tenant_id, action, days_delta, actor_id, note)
                values (?, 'CREATED', ?, ?, ?)
                """, tenantId, row.trialDays(), operator.userId(),
                "Provisioned from public trial request " + row.reference());

        // The invariant, checked rather than assumed. If it is ever false the whole
        // transaction goes, which is the right outcome: no workspace is better than
        // one with the wrong administrators.
        Map<String, Integer> roles = roleCounts(tenantId);
        int admins = roles.getOrDefault("TENANT_ADMIN", 0);
        int auditors = roles.getOrDefault("AUDITOR", 0);
        if (admins != 1 || auditors != 1) {
            throw new IllegalStateException("Refusing to complete provisioning for " + row.reference()
                    + ": a trial workspace must have exactly one TENANT_ADMIN and one AUDITOR, but this one "
                    + "has " + admins + " and " + auditors + ". Nothing was kept.");
        }

        List<ActivationLink> links = new ArrayList<>();
        links.add(issueActivation(row.id(), tenantId, adminUserId, row.workEmail(), row.fullName(),
                CrmRole.TENANT_ADMIN.name()));
        links.add(issueActivation(row.id(), tenantId, auditorId, auditorEmail(row),
                row.fullName() + " (auditor)", CrmRole.AUDITOR.name()));

        // Back to the operator's own workspace: platform.trial_request is
        // platform-scoped, and governance.audit_event is written against the
        // operator's tenant.
        bind(operator.tenantId());
        jdbc.update("""
                update platform.trial_request
                set status = 'PROVISIONED', provisioned_tenant_id = ?, provisioned_slug = ?,
                    reviewed_at = now(), reviewed_by = ?, reviewed_by_name = ?, updated_at = now()
                where id = ?
                """, tenantId, slug, operator.userId(), operator.displayName(), row.id());
        requests.logEvent(row.id(), "APPROVED", row.reference(), row.companyName(), row.workEmail(),
                row.emailDomain(), operator.userId(), operator.displayName(),
                "Approved for provisioning as workspace " + slug + ".",
                request == null ? null : request.note(), null, null);
        requests.logEvent(row.id(), "PROVISIONED", row.reference(), row.companyName(), row.workEmail(),
                row.emailDomain(), operator.userId(), operator.displayName(),
                "Workspace " + slug + " provisioned with a " + row.trialDays() + "-day trial ending " + end
                        + ", one TENANT_ADMIN and one AUDITOR.", null, null, null);
        for (ActivationLink link : links) {
            requests.logEvent(row.id(), "ACTIVATION_ISSUED", row.reference(), row.companyName(),
                    link.email(), row.emailDomain(), operator.userId(), operator.displayName(),
                    "One-time activation link issued for the " + link.role() + " account.", null, null, null);
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", row.reference());
        details.put("slug", slug);
        details.put("tenantId", tenantId.toString());
        details.put("trialDays", row.trialDays());
        details.put("trialEndsAt", end.toString());
        details.put("tenantAdminCount", admins);
        details.put("auditorCount", auditors);
        details.put("demoData", demoData);
        audit.recordWithReason("TRIAL_REQUEST_APPROVE", "TRIAL_REQUEST", row.id(),
                "Trial request " + row.reference() + " approved and provisioned as " + slug,
                request == null ? null : request.note(), details);

        return new ApprovalResult(row.id(), row.reference(), tenantId, slug, row.companyName(),
                row.trialDays(), start, end, true, admins, auditors, links, demoData,
                "Workspace " + slug + " is active with a " + row.trialDays() + "-day trial ending " + end
                        + ". Send each activation link to its owner — they are single-use, expire in "
                        + ACTIVATION_VALID_DAYS + " days, and are not stored anywhere in recoverable form.");
    }

    // ------------------------------------------------------------------ pieces

    private UUID ensureAuditor(UUID tenantId, TrialRequestService.TrialRequestRow row) {
        String email = auditorEmail(row);
        List<UUID> existing = jdbc.queryForList("""
                select id from identity.app_user where tenant_id = ? and lower(email) = ?
                """, UUID.class, tenantId, email);
        if (!existing.isEmpty()) return existing.get(0);
        UUID id = UUID.randomUUID();
        // A hash of an unguessable value nobody ever sees. The account is reachable
        // only through its activation link until its owner sets a password.
        String hash = bcrypt.encode(generatedPassword());
        jdbc.update("""
                insert into identity.app_user
                  (id, tenant_id, email, password_hash, display_name, role, active, must_change_password)
                values (?, ?, ?, ?, ?, 'AUDITOR', true, true)
                """, id, tenantId, email, hash, row.fullName() + " (auditor)");
        jdbc.update("insert into identity.password_history(tenant_id, user_id, password_hash) values (?, ?, ?)",
                tenantId, id, hash);
        jdbc.update("update identity.app_user set must_change_password = true where tenant_id = ? and role = 'TENANT_ADMIN'",
                tenantId);
        return id;
    }

    /**
     * The auditor address is derived from the requester's own, using the +suffix
     * convention every mainstream provider routes back to the same mailbox. That
     * is intentional for a trial: the evaluator can activate both accounts without
     * needing a second person, and the address is unambiguously theirs.
     */
    private static String auditorEmail(TrialRequestService.TrialRequestRow row) {
        String email = row.workEmail();
        int at = email.lastIndexOf('@');
        return (email.substring(0, at) + "+auditor" + email.substring(at)).toLowerCase(Locale.ROOT);
    }

    private ActivationLink issueActivation(UUID trialRequestId, UUID tenantId, UUID userId, String email,
                                           String displayName, String role) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = Instant.now().plus(ACTIVATION_VALID_DAYS, ChronoUnit.DAYS);
        jdbc.update("""
                insert into platform.trial_activation
                  (trial_request_id, tenant_id, user_id, email, role, token_hash, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, trialRequestId, tenantId, userId, email, role, sha256(token),
                java.sql.Timestamp.from(expiresAt));
        return new ActivationLink(email, displayName, role,
                publicBaseUrl + "/activate/" + token, expiresAt);
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    private Map<String, Integer> roleCounts(UUID tenantId) {
        bind(tenantId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select role, count(*) as n from identity.app_user
                where tenant_id = ? and active = true group by role
                """, tenantId)) {
            counts.put((String) row.get("role"), ((Number) row.get("n")).intValue());
        }
        return counts;
    }

    private LocalDate[] trialWindow(UUID tenantId) {
        List<LocalDate[]> found = jdbc.query("""
                select trial_start_at, trial_ends_at from platform.company_account where tenant_id = ?
                """, (rs, i) -> new LocalDate[]{rs.getObject("trial_start_at", LocalDate.class),
                rs.getObject("trial_ends_at", LocalDate.class)}, tenantId);
        return found.isEmpty() ? new LocalDate[]{null, null} : found.get(0);
    }

    /**
     * A workspace slug from a company name. Slugs are immutable once assigned and
     * appear in URLs, so a collision is resolved by suffixing rather than by
     * failing the approval — the operator did nothing wrong.
     */
    String uniqueSlug(String source) {
        String base = slugify(source);
        String candidate = base;
        for (int suffix = 2; suffix < 200; suffix++) {
            if (jdbc.queryForList("select 1 from platform.tenant where lower(slug) = ?", Integer.class,
                    candidate).isEmpty()) {
                return candidate;
            }
            String tail = "-" + suffix;
            candidate = base.substring(0, Math.min(base.length(), 41 - tail.length())) + tail;
        }
        throw new ConflictException("Could not derive a free workspace slug from \"" + source
                + "\". Supply one explicitly on the approval.");
    }

    static String slugify(String source) {
        String cleaned = (source == null ? "" : source).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0))) {
            cleaned = "trial-" + cleaned;
            cleaned = cleaned.replaceAll("-+$", "");
        }
        if (cleaned.length() > 41) cleaned = cleaned.substring(0, 41).replaceAll("-+$", "");
        while (cleaned.length() < 3) cleaned = cleaned + "x";
        return cleaned;
    }

    /** Meets the default composition policy (12+, all four classes) by construction. */
    static String generatedPassword() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            out.append(UPPER[RANDOM.nextInt(UPPER.length)]);
            out.append(LOWER[RANDOM.nextInt(LOWER.length)]);
            out.append(DIGIT[RANDOM.nextInt(DIGIT.length)]);
            out.append(SYMBOL[RANDOM.nextInt(SYMBOL.length)]);
        }
        return out.toString();
    }

    private void bind(UUID tenantId) {
        jdbc.query("select set_config('app.tenant_id', ?, true)", rs -> null, tenantId.toString());
    }

    // ------------------------------------------------------------------ demo data

    /**
     * Seeds a small but coherent book of business, so an evaluator sees the product
     * doing its job on day one instead of an empty grid. Modest on purpose: enough
     * to exercise accounts, contacts, leads and a pipeline with realistic values,
     * not so much that it takes the trial's own data to find.
     */
    private List<String> seedDemoData(UUID tenantId, UUID ownerId, String companyName) {
        List<String> seeded = new ArrayList<>();
        Object[][] accounts = {
                {"Halden Marine Works", "Industrial Manufacturing", "CUSTOMER", 780, 42_000_000L, "haldenmarine.example"},
                {"Corvid Energy Systems", "Energy and Utilities", "CUSTOMER", 2400, 190_000_000L, "corvidenergy.example"},
                {"Lattice Rail Infrastructure", "Transport and Logistics", "PROSPECT", 1150, 88_000_000L, "latticerail.example"},
                {"Pentworth Cold Chain", "Food and Beverage", "PROSPECT", 320, 21_000_000L, "pentworth.example"},
        };
        List<UUID> accountIds = new ArrayList<>();
        for (Object[] a : accounts) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into crm.account
                      (id, tenant_id, name, legal_name, industry, record_type, owner_id, employee_count,
                       annual_revenue, currency_code, email_domain, website, segment, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'USD', ?, ?, 'ENTERPRISE', 'ACTIVE')
                    """, id, tenantId, a[0], a[0] + " Ltd", a[1], a[2], ownerId, a[3], a[4], a[5],
                    "https://" + a[5]);
            accountIds.add(id);
        }
        seeded.add(accounts.length + " accounts");

        Object[][] contacts = {
                {0, "Ingrid", "Halvorsen", "Director of Operations", "DIRECTOR", "ingrid.halvorsen@haldenmarine.example"},
                {0, "Tomas", "Renner", "Procurement Lead", "MANAGER", "tomas.renner@haldenmarine.example"},
                {1, "Adaeze", "Nwosu", "Chief Operating Officer", "C_LEVEL", "adaeze.nwosu@corvidenergy.example"},
                {1, "Peter", "Lindqvist", "Head of Asset Management", "DIRECTOR", "peter.lindqvist@corvidenergy.example"},
                {2, "Sara", "Bhattacharya", "VP Engineering", "VP", "sara.bhattacharya@latticerail.example"},
                {3, "Callum", "Reid", "Logistics Manager", "MANAGER", "callum.reid@pentworth.example"},
        };
        for (Object[] c : contacts) {
            jdbc.update("""
                    insert into crm.contact
                      (tenant_id, account_id, first_name, last_name, title, seniority, email, owner_id, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, tenantId, accountIds.get((Integer) c[0]), c[1], c[2], c[3], c[4], c[5], ownerId);
        }
        seeded.add(contacts.length + " contacts");

        Object[][] leads = {
                {"Miriam", "Osei", "Kestrel Precision Castings", "Head of Supply Chain",
                        "miriam.osei@kestrelcastings.example", "NEW", "WEBSITE", "WARM"},
                {"Jonas", "Weber", "Aldershot Composites", "Operations Director",
                        "jonas.weber@aldershotcomposites.example", "NEW", "EVENT", "HOT"},
                {"Lucia", "Ferrari", "Northgate Water Utilities", "Programme Manager",
                        "lucia.ferrari@northgatewater.example", "QUALIFIED", "REFERRAL", "HOT"},
                {"Owen", "Pryce", "Bramley Agritech", "Commercial Lead",
                        "owen.pryce@bramleyagritech.example", "QUALIFIED", "WEBSITE", "WARM"},
                {"Hana", "Kobayashi", "Seiryu Machine Tools", "Director of Sales Operations",
                        "hana.kobayashi@seiryutools.example", "NEW", "PARTNER", "COLD"},
        };
        for (Object[] l : leads) {
            jdbc.update("""
                    insert into crm.lead
                      (tenant_id, first_name, last_name, company, title, email, status, source, rating, owner_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], ownerId);
        }
        seeded.add(leads.length + " leads");

        List<UUID> pipelineIds = jdbc.queryForList(
                "select id from pipeline.pipeline where tenant_id = ? order by is_default desc, created_at",
                UUID.class, tenantId);
        Map<String, UUID> stages = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select id, name from crm.pipeline_stage where tenant_id = ? order by sort_order", tenantId)) {
            stages.put((String) row.get("name"), (UUID) row.get("id"));
        }
        if (!pipelineIds.isEmpty() && !stages.isEmpty()) {
            UUID pipelineId = pipelineIds.get(0);
            Object[][] opportunities = {
                    {0, "Halden Marine — dry dock scheduling rollout", "Proposal", 480_000L, 45, 60},
                    {1, "Corvid Energy — turbine maintenance platform", "Negotiation", 1_250_000L, 70, 30},
                    {2, "Lattice Rail — depot asset visibility", "Qualifying", 320_000L, 20, 90},
                    {3, "Pentworth — cold chain compliance pack", "Commit", 165_000L, 85, 21},
            };
            int created = 0;
            for (Object[] o : opportunities) {
                UUID stageId = stages.get((String) o[2]);
                if (stageId == null) continue;
                jdbc.update("""
                        insert into sales.opportunity
                          (tenant_id, name, account_id, stage_id, pipeline_id, amount, owner_id, close_date,
                           currency_code, probability, forecast_category, next_step, stage_entered_at)
                        values (?, ?, ?, ?, ?, ?, ?, current_date + ?, 'USD', ?, 'PIPELINE',
                                'Confirm evaluation criteria with the buying group', now())
                        """, tenantId, o[1], accountIds.get((Integer) o[0]), stageId, pipelineId, o[3],
                        ownerId, o[5], o[4]);
                created++;
            }
            if (created > 0) seeded.add(created + " open opportunities");
        }

        seeded.add("workspace named for " + companyName);
        return seeded;
    }
}
