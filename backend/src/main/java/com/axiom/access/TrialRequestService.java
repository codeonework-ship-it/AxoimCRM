package com.axiom.access;

import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.PlatformSession;
import com.axiom.tenancy.TenantContext;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Public trial self-registration, and the review actions over the resulting queue.
 *
 * <p>This is the one part of Axiom that an unauthenticated stranger can reach, so
 * it is written as a front door rather than as an internal service.
 *
 * <h2>Refusals are outcomes, not exceptions</h2>
 * Every path — accepted, duplicate, free-mail, rate-limited, malformed — returns a
 * {@link Decision} and the transaction <em>commits</em>. That is deliberate: if a
 * refusal threw, the {@code trial_request_event} row recording the refusal would
 * roll back with it, and the abuse patterns the guards exist to catch would be
 * invisible precisely because the guards worked. The controller maps the decision
 * to an HTTP status.
 *
 * <h2>A duplicate is not an error</h2>
 * Somebody who clicks submit twice, or comes back a week later having lost the
 * email, gets their ORIGINAL reference back with the same wording and the same
 * shape of response as a first-time submission. No second row is created — the
 * service checks, and a partial unique index enforces it even under a race.
 * Returning an identical response shape also means the endpoint cannot be used to
 * probe whether an address, company or workspace already exists.
 *
 * <h2>What the abuse guards actually do</h2>
 * Free-mail and disposable domains are refused with advice; per-IP and per-domain
 * windows refuse politely with a time to come back. None of it is a substitute for
 * a WAF, and it is not claimed to be: it stops one person with a script, not a
 * botnet.
 */
@Service
public class TrialRequestService {

    /** Trial length granted on approval. Also reported to the requester up front. */
    public static final int TRIAL_DAYS = 30;

    /** A PENDING request older than this is swept to EXPIRED. */
    public static final int PENDING_EXPIRY_DAYS = 30;

    static final int MAX_PER_IP_PER_HOUR = 3;
    static final int MAX_PER_IP_PER_DAY = 8;
    static final int MAX_PER_DOMAIN_PER_DAY = 3;
    static final int MAX_PER_DOMAIN_PER_MONTH = 10;

    /**
     * Pragmatic address shape. Deliberately not RFC 5322 — a regex that accepts
     * every legal address accepts almost anything, and the real proof of an
     * address is that mail to it arrives.
     */
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?"
                    + "(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$");

    private static final List<String> OPEN_STATUSES = List.of("PENDING", "APPROVED");

    private final JdbcTemplate jdbc;
    private final PlatformSession platformSession;

    public TrialRequestService(JdbcTemplate jdbc, PlatformSession platformSession) {
        this.jdbc = jdbc;
        this.platformSession = platformSession;
    }

    // ------------------------------------------------------------------ contracts

    /** Exactly the shape the public form posts. */
    public record Submission(String companyName, String workEmail, String fullName, String jobTitle,
                             String companySize, String country, String notes) {}

    /**
     * The result of an intake attempt.
     *
     * @param httpStatus what the controller should return
     * @param code       machine-readable outcome; null when accepted
     * @param reference  present only when a request exists (new or pre-existing)
     */
    public record Decision(int httpStatus, String code, String reference, String status,
                           int trialDays, String message) {}

    public record TrialRequestRow(UUID id, String reference, String companyName, String workEmail,
                                  String emailDomain, String fullName, String jobTitle,
                                  String companySize, String country, String notes, String status,
                                  int trialDays, Instant submittedAt, Instant reviewedAt,
                                  String reviewedByName, UUID provisionedTenantId,
                                  String provisionedSlug, String rejectReason, String sourceIp) {}

    // ------------------------------------------------------------------ intake

    /**
     * Handles one public submission. Never throws for a rejected submission; the
     * refusal is the return value so the event trail survives the commit.
     */
    @Transactional
    public Decision submit(Submission raw, String sourceIp, String userAgent) {
        intakeSession();

        String companyName = trim(raw == null ? null : raw.companyName());
        String workEmail = lower(trim(raw == null ? null : raw.workEmail()));
        String fullName = trim(raw == null ? null : raw.fullName());
        String jobTitle = trim(raw == null ? null : raw.jobTitle());
        String companySize = trim(raw == null ? null : raw.companySize());
        String country = trim(raw == null ? null : raw.country());
        String notes = trim(raw == null ? null : raw.notes());

        String validation = validate(companyName, workEmail, fullName, jobTitle, companySize, country, notes);
        if (validation != null) {
            logEvent(null, "REFUSED_VALIDATION", null, companyName, workEmail, domainOf(workEmail),
                    null, "Anonymous (public trial form)", validation, null, sourceIp, userAgent);
            return new Decision(400, "TRIAL_VALIDATION_FAILED", null, null, TRIAL_DAYS, validation);
        }

        String domain = domainOf(workEmail);
        if (FreeMailDomains.isBlocked(domain)) {
            String message = FreeMailDomains.refusal(domain);
            logEvent(null, "REFUSED_FREE_MAIL", null, companyName, workEmail, domain, null,
                    "Anonymous (public trial form)", message, null, sourceIp, userAgent);
            return new Decision(400, "TRIAL_WORK_EMAIL_REQUIRED", null, null, TRIAL_DAYS, message);
        }

        // Duplicate detection BEFORE rate limiting, so somebody who resubmits after
        // losing their reference is helped rather than throttled.
        String existing = openReferenceFor(workEmail);
        if (existing != null) {
            logEvent(null, "DUPLICATE_SUPPRESSED", existing, companyName, workEmail, domain, null,
                    "Anonymous (public trial form)",
                    "Repeat submission matched an open request; no second row was created.",
                    null, sourceIp, userAgent);
            return accepted(existing, "PENDING");
        }

        String throttle = rateLimit(sourceIp, domain);
        if (throttle != null) {
            logEvent(null, "REFUSED_RATE_LIMIT", null, companyName, workEmail, domain, null,
                    "Anonymous (public trial form)", throttle, null, sourceIp, userAgent);
            return new Decision(429, "TRIAL_RATE_LIMITED", null, null, TRIAL_DAYS, throttle);
        }

        String reference = nextReference();
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into platform.trial_request
                      (id, reference, company_name, work_email, email_domain, full_name, job_title,
                       company_size, country, notes, status, trial_days, source_ip, user_agent)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                    """, id, reference, companyName, workEmail, domain, fullName, jobTitle,
                    companySize, country, notes, TRIAL_DAYS, sourceIp, userAgent);
        } catch (DuplicateKeyException race) {
            // Two submissions from the same address arrived together. The partial
            // unique index picked a winner; return the winner's reference.
            String winner = openReferenceFor(workEmail);
            if (winner == null) throw race;
            logEvent(null, "DUPLICATE_SUPPRESSED", winner, companyName, workEmail, domain, null,
                    "Anonymous (public trial form)",
                    "Concurrent submission collapsed onto the existing request.", null, sourceIp, userAgent);
            return accepted(winner, "PENDING");
        }

        logEvent(id, "SUBMITTED", reference, companyName, workEmail, domain, null,
                "Anonymous (public trial form)",
                "Trial request received from " + companyName + " (" + domain + ").",
                null, sourceIp, userAgent);
        return accepted(reference, "PENDING");
    }

    private Decision accepted(String reference, String status) {
        return new Decision(201, null, reference, status, TRIAL_DAYS,
                "Thanks — your request is with our team under reference " + reference + ". "
                        + "We review new trial requests on business days and will email you as soon as your "
                        + "workspace is ready. Keep this reference for any follow-up.");
    }

    /** @return the first failure in human words, or null when everything is acceptable. */
    private String validate(String companyName, String workEmail, String fullName, String jobTitle,
                            String companySize, String country, String notes) {
        if (isBlank(companyName)) return "Tell us your company name so we know who the workspace is for.";
        if (companyName.length() < 2 || companyName.length() > 160) {
            return "The company name should be between 2 and 160 characters.";
        }
        if (isBlank(fullName)) return "Tell us your name so we know who to contact.";
        if (fullName.length() < 2 || fullName.length() > 120) {
            return "Your name should be between 2 and 120 characters.";
        }
        if (isBlank(workEmail)) return "A work email address is required — it is how we send your workspace.";
        if (workEmail.length() > 254 || !EMAIL.matcher(workEmail).matches()) {
            return "That does not look like a valid email address. Check it and try again.";
        }
        String domain = domainOf(workEmail);
        if (domain == null || !domain.contains(".") || domain.length() > 253) {
            return "That does not look like a valid email address. Check it and try again.";
        }
        if (jobTitle != null && jobTitle.length() > 120) return "Job title is limited to 120 characters.";
        if (companySize != null && companySize.length() > 40) return "Company size is limited to 40 characters.";
        if (country != null && country.length() > 80) return "Country is limited to 80 characters.";
        if (notes != null && notes.length() > 2000) {
            return "Please keep what you want to evaluate under 2000 characters.";
        }
        return null;
    }

    /** @return a polite refusal, or null when the submission is within every window. */
    private String rateLimit(String sourceIp, String domain) {
        if (sourceIp != null && !sourceIp.isBlank()) {
            if (count("""
                    select count(*) from platform.trial_request
                    where source_ip = ? and submitted_at > now() - interval '1 hour'
                    """, sourceIp) >= MAX_PER_IP_PER_HOUR) {
                return "We have already taken several trial requests from your network in the last hour, so "
                        + "this one was not submitted. Please try again in an hour, or email sales and a person "
                        + "will pick it up straight away.";
            }
            if (count("""
                    select count(*) from platform.trial_request
                    where source_ip = ? and submitted_at > now() - interval '24 hours'
                    """, sourceIp) >= MAX_PER_IP_PER_DAY) {
                return "That is more trial requests from your network than we accept in a day, so this one was "
                        + "not submitted. Please try again tomorrow, or email sales and a person will pick it up.";
            }
        }
        if (count("""
                select count(*) from platform.trial_request
                where email_domain = ? and submitted_at > now() - interval '24 hours'
                """, domain) >= MAX_PER_DOMAIN_PER_DAY) {
            return "We already have recent trial requests from your organisation, so this one was not "
                    + "submitted. Ask your colleague for the reference they were given, or email sales and we "
                    + "will join the requests up.";
        }
        if (count("""
                select count(*) from platform.trial_request
                where email_domain = ? and submitted_at > now() - interval '30 days'
                """, domain) >= MAX_PER_DOMAIN_PER_MONTH) {
            return "Your organisation has reached the number of trial requests we accept in a month. Email "
                    + "sales and a person will sort out the access you need directly.";
        }
        return null;
    }

    private String openReferenceFor(String workEmail) {
        List<String> found = jdbc.queryForList("""
                select reference from platform.trial_request
                where lower(work_email) = ? and status in ('PENDING','APPROVED')
                order by submitted_at limit 1
                """, String.class, workEmail);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * {@code TRL-<year>-<0001>}. Sequential and unique because it comes from a
     * sequence: two concurrent submissions cannot be handed the same number, which
     * a {@code max()+1} read would happily do.
     */
    String nextReference() {
        Long n = jdbc.queryForObject("select nextval('platform.trial_request_reference_seq')", Long.class);
        return formatReference(Year.now().getValue(), n == null ? 1L : n);
    }

    static String formatReference(int year, long ordinal) {
        return String.format("TRL-%d-%04d", year, ordinal);
    }

    // ------------------------------------------------------------------ review queue

    @Transactional(readOnly = true)
    public List<TrialRequestRow> list(String status) {
        platformSession.requirePlatformAndGrant();
        String filter = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)
                ? null : status.trim().toUpperCase(Locale.ROOT);
        if (filter == null) {
            return jdbc.query("""
                    select * from platform.trial_request order by submitted_at desc
                    """, (rs, i) -> mapRow(rs));
        }
        return jdbc.query("""
                select * from platform.trial_request where status = ? order by submitted_at desc
                """, (rs, i) -> mapRow(rs), filter);
    }

    @Transactional(readOnly = true)
    public TrialRequestRow get(UUID id) {
        platformSession.requirePlatformAndGrant();
        return load(id);
    }

    /** Package-private: the provisioning service reads inside its own transaction. */
    TrialRequestRow load(UUID id) {
        try {
            return jdbc.queryForObject("select * from platform.trial_request where id = ?",
                    (rs, i) -> mapRow(rs), id);
        } catch (EmptyResultDataAccessException e) {
            throw new NotFoundException("No such trial request");
        }
    }

    /**
     * Declines a request. A reason is mandatory — the person who has to answer
     * "why was my trial refused" three weeks later is not the person who refused it.
     */
    @Transactional
    public TrialRequestRow reject(UUID id, String reason) {
        platformSession.requirePlatformAndGrant();
        requireWritablePlatformRole("Rejecting a trial request");
        String cleaned = trim(reason);
        if (isBlank(cleaned)) {
            throw new IllegalArgumentException("Give a reason for declining this trial request. "
                    + "It is recorded against the request and is what anyone reviewing the decision later "
                    + "will read.");
        }
        TrialRequestRow current = load(id);
        if ("PROVISIONED".equals(current.status())) {
            throw new ConflictException("That request has already been provisioned into a live workspace. "
                    + "Suspend or terminate the workspace instead of rejecting the request.");
        }
        if ("REJECTED".equals(current.status())) return current;
        TenantContext.Principal operator = TenantContext.get();
        jdbc.update("""
                update platform.trial_request
                set status = 'REJECTED', reject_reason = ?, reviewed_at = now(), reviewed_by = ?,
                    reviewed_by_name = ?, updated_at = now()
                where id = ?
                """, cleaned, operator.userId(), operator.displayName(), id);
        logEvent(id, "REJECTED", current.reference(), current.companyName(), current.workEmail(),
                current.emailDomain(), operator.userId(), operator.displayName(),
                "Trial request declined.", cleaned, null, null);
        return load(id);
    }

    /**
     * Sweeps requests nobody acted on. An untouched request sitting PENDING for a
     * month is not pending, it is forgotten; saying so is more honest than a queue
     * that only grows.
     */
    @Transactional
    public int expireStale() {
        platformSession.requirePlatformAndGrant();
        requireWritablePlatformRole("Expiring stale trial requests");
        List<TrialRequestRow> stale = jdbc.query("""
                select * from platform.trial_request
                where status = 'PENDING' and submitted_at < now() - make_interval(days => ?)
                """, (rs, i) -> mapRow(rs), PENDING_EXPIRY_DAYS);
        TenantContext.Principal operator = TenantContext.get();
        for (TrialRequestRow row : stale) {
            jdbc.update("""
                    update platform.trial_request
                    set status = 'EXPIRED', reviewed_at = now(), reviewed_by = ?, reviewed_by_name = ?,
                        updated_at = now()
                    where id = ? and status = 'PENDING'
                    """, operator.userId(), operator.displayName(), row.id());
            logEvent(row.id(), "EXPIRED", row.reference(), row.companyName(), row.workEmail(),
                    row.emailDomain(), operator.userId(), operator.displayName(),
                    "No action was taken within " + PENDING_EXPIRY_DAYS + " days.", null, null, null);
        }
        return stale.size();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Marks this transaction as the public intake path so the RLS policy on
     * platform.trial_request admits rows. SET LOCAL — it dies with the
     * transaction and cannot leak onto a pooled connection.
     */
    void intakeSession() {
        jdbc.query("select set_config('app.trial_intake', 'on', true)", rs -> null);
    }

    void logEvent(UUID requestId, String action, String reference, String companyName, String workEmail,
                  String domain, UUID actorId, String actorName, String detail, String reason,
                  String sourceIp, String userAgent) {
        jdbc.update("""
                insert into platform.trial_request_event
                  (trial_request_id, action, reference, company_name, work_email, email_domain,
                   actor_id, actor_name, detail, reason, source_ip, user_agent, correlation_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, requestId, action, reference, companyName, workEmail, domain, actorId,
                actorName == null ? "Anonymous (public trial form)" : actorName,
                detail, reason, sourceIp, userAgent, MDC.get("correlationId"));
    }

    private long count(String sql, Object arg) {
        Long value = jdbc.queryForObject(sql, Long.class, arg);
        return value == null ? 0L : value;
    }

    static TrialRequestRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TrialRequestRow(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("company_name"),
                rs.getString("work_email"),
                rs.getString("email_domain"),
                rs.getString("full_name"),
                rs.getString("job_title"),
                rs.getString("company_size"),
                rs.getString("country"),
                rs.getString("notes"),
                rs.getString("status"),
                rs.getInt("trial_days"),
                rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toInstant(),
                rs.getString("reviewed_by_name"),
                rs.getObject("provisioned_tenant_id", UUID.class),
                rs.getString("provisioned_slug"),
                rs.getString("reject_reason"),
                rs.getString("source_ip"));
    }

    static void requireWritablePlatformRole(String action) {
        CrmRole role = CrmRole.current(TenantContext.get().role());
        if (!role.platform()) {
            throw new ForbiddenException(action + " requires a platform operator role");
        }
        if (role.readOnly()) {
            throw new ForbiddenException(action + " is not available to a read-only audit role");
        }
    }

    static String domainOf(String email) {
        if (email == null) return null;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return null;
        return email.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Whether a status still counts as an open request. Used by the masters UI. */
    public static boolean isOpen(String status) {
        return OPEN_STATUSES.contains(status);
    }
}
