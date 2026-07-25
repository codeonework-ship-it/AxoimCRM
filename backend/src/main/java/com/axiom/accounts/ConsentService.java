package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-ACC-011 — per-channel consent with lawful basis, source and timestamp, and
 * suppression enforced at the point of send or dial.
 *
 * <p><b>Consent is append-only.</b> A withdrawal is a new row. This service only
 * ever issues INSERTs against {@code crm.consent_record}, and V40 removes UPDATE
 * and DELETE from the runtime role entirely so a future code path cannot quietly
 * change that. Consent history is the evidence that the organization behaved
 * lawfully; the act of changing consent must not destroy the proof of the
 * previous position.
 *
 * <p><b>A suppressed send is blocked, not warned.</b> {@link #attemptOutreach}
 * returns a refusal and writes both a ledger row and an audit event. There is no
 * override parameter, because a "send anyway" flag is the only feature request
 * that reliably turns a compliance control into a formality.
 */
@Service
public class ConsentService {

    /**
     * Purposes that require a positive opt-in. For anything else, silence is not
     * consent but neither is it a withdrawal, so the send proceeds on the
     * recorded lawful basis.
     */
    private static final Set<String> OPT_IN_REQUIRED = Set.of("MARKETING", "RESEARCH");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ActorSession actor;

    public ConsentService(JdbcTemplate jdbc, AuditService audit, ActorSession actor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
    }

    // --------------------------------------------------------------- contracts

    public record ConsentRequest(@NotBlank String channel, @NotBlank String purpose,
                                 @NotBlank String state, @NotBlank String lawfulBasis,
                                 @NotBlank String source, String evidenceRef, String note) {}

    public record ConsentEntry(UUID id, String subjectType, UUID subjectId, String channel,
                               String purpose, String state, String lawfulBasis, String source,
                               String evidenceRef, Instant capturedAt, Instant grantedAt,
                               Instant withdrawnAt, String recordedByName, String note) {}

    public record ConsentPosition(String channel, String purpose, String state, String lawfulBasis,
                                  Instant effectiveSince, boolean suppressed, String explanation) {}

    public record ConsentView(String subjectType, UUID subjectId, List<ConsentPosition> currentPositions,
                              List<ConsentEntry> history, String appendOnlyNote) {}

    public record OutreachRequest(@NotBlank String channel, @NotBlank String purpose,
                                  String origin, String subjectLine) {}

    public record OutreachResult(boolean allowed, String outcome, String channel, String purpose,
                                 UUID attemptId, String reason, String remedy) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public ConsentView view(String subjectType, UUID subjectId) {
        String type = AccountService.upper(subjectType);
        List<ConsentEntry> history = history(type, subjectId);
        return new ConsentView(type, subjectId, positions(history), history,
                "Consent records are append-only. A withdrawal adds a row; nothing above is ever "
                + "edited or removed, so the full history stays available as evidence.");
    }

    private List<ConsentEntry> history(String subjectType, UUID subjectId) {
        return jdbc.query("""
                select c.id, c.subject_type, c.subject_id, c.channel, c.purpose, c.state,
                       c.lawful_basis, c.source, c.evidence_ref, c.captured_at, c.granted_at,
                       c.withdrawn_at, u.display_name as recorded_by_name, c.note
                from crm.consent_record c
                left join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.recorded_by
                where c.tenant_id = ? and c.subject_type = ? and c.subject_id = ?
                order by c.captured_at desc, c.id
                """, (rs, i) -> new ConsentEntry(rs.getObject("id", UUID.class),
                rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                rs.getString("channel"), rs.getString("purpose"), rs.getString("state"),
                rs.getString("lawful_basis"), rs.getString("source"), rs.getString("evidence_ref"),
                rs.getTimestamp("captured_at").toInstant(),
                rs.getTimestamp("granted_at") == null ? null : rs.getTimestamp("granted_at").toInstant(),
                rs.getTimestamp("withdrawn_at") == null ? null : rs.getTimestamp("withdrawn_at").toInstant(),
                rs.getString("recorded_by_name"), rs.getString("note")),
                TenantContext.get().tenantId(), subjectType, subjectId);
    }

    /**
     * The current position per channel and purpose, derived from the newest row.
     * Derived rather than stored: a stored "current consent" column is a second
     * source of truth that will eventually disagree with the evidence.
     */
    static List<ConsentPosition> positions(List<ConsentEntry> historyNewestFirst) {
        Map<String, ConsentPosition> latest = new LinkedHashMap<>();
        for (ConsentEntry entry : historyNewestFirst) {
            String key = entry.channel() + "|" + entry.purpose();
            if (latest.containsKey(key)) continue;
            boolean suppressed = !"GRANTED".equals(entry.state());
            latest.put(key, new ConsentPosition(entry.channel(), entry.purpose(), entry.state(),
                    entry.lawfulBasis(), entry.capturedAt(), suppressed,
                    switch (entry.state()) {
                        case "GRANTED" -> "Permission given on " + entry.capturedAt() + " (" + entry.source() + ").";
                        case "WITHDRAWN" -> "Permission withdrawn on " + entry.capturedAt()
                                + " (" + entry.source() + "). Sends on this channel and purpose are blocked.";
                        default -> "Permission was never given. Sends on this channel and purpose are blocked.";
                    }));
        }
        return List.copyOf(latest.values());
    }

    // -------------------------------------------------------------- recording

    /**
     * Appends a consent row. Never an UPDATE — see the class comment, and V40's
     * privilege grants, which make that a matter of permissions rather than of
     * discipline.
     */
    @Transactional
    public ConsentView record(String subjectType, UUID subjectId, ConsentRequest request) {
        actor.bind();
        String type = AccountService.upper(subjectType);
        String state = AccountService.upper(request.state());
        String channel = AccountService.upper(request.channel());
        String purpose = AccountService.upper(request.purpose());
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    insert into crm.consent_record
                      (tenant_id, subject_type, subject_id, channel, purpose, state, lawful_basis,
                       source, evidence_ref, captured_at, granted_at, withdrawn_at, recorded_by, note)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, TenantContext.get().tenantId(), type, subjectId, channel, purpose, state,
                    AccountService.upper(request.lawfulBasis()), AccountService.upper(request.source()),
                    AccountService.blankToNull(request.evidenceRef()), Timestamp.from(now),
                    "GRANTED".equals(state) ? Timestamp.from(now) : null,
                    "WITHDRAWN".equals(state) ? Timestamp.from(now) : null,
                    TenantContext.get().userId(), AccountService.blankToNull(request.note()));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(AccountService.databaseMessage(ex,
                    "That consent record could not be saved. Check the channel, purpose, state, "
                    + "lawful basis and source values."));
        }
        audit.record("CONSENT_" + state, type, subjectId,
                "Consent " + state.toLowerCase(Locale.ROOT) + " for " + channel + " / " + purpose,
                Map.of("channel", channel, "purpose", purpose, "state", state,
                        "lawfulBasis", AccountService.upper(request.lawfulBasis()),
                        "source", AccountService.upper(request.source()),
                        "evidenceRef", AccountService.nullSafe(request.evidenceRef(), "")));
        return view(type, subjectId);
    }

    /**
     * Appends DSR-driven withdrawal rows for every currently granted consent
     * position. This is intentionally not an update: the original grants remain
     * evidence, and the erasure request records the withdrawal as a later legal
     * event. Returns the number of consent positions withdrawn.
     */
    @Transactional
    public int withdrawAllForSubject(String subjectType, UUID subjectId, String subjectEmail, String evidenceRef) {
        actor.bind();
        String type = AccountService.upper(subjectType);
        if (subjectId == null || (!"CONTACT".equals(type) && !"LEAD".equals(type))) {
            return 0;
        }
        List<ConsentPosition> granted = positions(history(type, subjectId)).stream()
                .filter(position -> "GRANTED".equals(position.state()))
                .toList();
        if (granted.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        int count = 0;
        for (ConsentPosition position : granted) {
            jdbc.update("""
                    insert into crm.consent_record
                      (tenant_id, subject_type, subject_id, channel, purpose, state, lawful_basis,
                       source, evidence_ref, captured_at, withdrawn_at, recorded_by, note)
                    values (?, ?, ?, ?, ?, 'WITHDRAWN', ?, 'DATA_SUBJECT_REQUEST', ?, ?, ?, ?, ?)
                    """, TenantContext.get().tenantId(), type, subjectId, position.channel(),
                    position.purpose(), position.lawfulBasis(), AccountService.blankToNull(evidenceRef),
                    Timestamp.from(now), Timestamp.from(now), TenantContext.get().userId(),
                    "Consent withdrawn during data-subject erasure"
                            + (subjectEmail == null || subjectEmail.isBlank()
                            ? "." : " for " + subjectEmail.trim().toLowerCase(Locale.ROOT) + "."));
            count++;
        }
        audit.record("CONSENT_DSR_WITHDRAWAL", type, subjectId,
                "Withdrew " + count + " active consent position(s) during data-subject erasure",
                Map.of("count", count, "evidenceRef", AccountService.nullSafe(evidenceRef, "")));
        return count;
    }

    // --------------------------------------------------------- suppression gate

    /**
     * The point-of-send gate. Every channel goes through here — UI, API, cadence,
     * automation and integration alike, distinguished only by {@code origin} so
     * the ledger says who tried.
     *
     * <p>Returns a refusal rather than throwing so the ledger row and the audit
     * event commit with the transaction. A thrown exception would roll back the
     * very evidence FR-ACC-011 requires.
     */
    @Transactional
    public OutreachResult attemptOutreach(String subjectType, UUID subjectId, OutreachRequest request) {
        actor.bind();
        String type = AccountService.upper(subjectType);
        String channel = AccountService.upper(request.channel());
        String purpose = AccountService.upper(request.purpose());
        String origin = AccountService.nullSafe(AccountService.upper(request.origin()), "UI");

        String block = suppressionReason(type, subjectId, channel, purpose);
        UUID attemptId = jdbc.queryForObject("""
                insert into crm.outreach_attempt
                  (tenant_id, subject_type, subject_id, channel, purpose, origin, outcome,
                   block_reason, subject_line, attempted_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, TenantContext.get().tenantId(), type, subjectId, channel, purpose,
                origin, block == null ? "ALLOWED" : "BLOCKED", block,
                AccountService.blankToNull(request.subjectLine()), TenantContext.get().userId());

        if (block != null) {
            audit.record("OUTREACH_BLOCKED", type, subjectId,
                    "Blocked " + channel + " outreach for " + purpose + ": " + block,
                    Map.of("channel", channel, "purpose", purpose, "origin", origin,
                            "outcome", "BLOCKED", "blockReason", block, "attemptId", attemptId.toString()));
            return new OutreachResult(false, "BLOCKED", channel, purpose, attemptId, block,
                    "Do not send. If the person has since given permission, record a new consent "
                    + "entry for this channel and purpose first — the block lifts from the new record, "
                    + "and the old one stays in the history.");
        }
        audit.record("OUTREACH_ALLOWED", type, subjectId,
                "Allowed " + channel + " outreach for " + purpose,
                Map.of("channel", channel, "purpose", purpose, "origin", origin,
                        "outcome", "ALLOWED", "attemptId", attemptId.toString()));
        return new OutreachResult(true, "ALLOWED", channel, purpose, attemptId, null, null);
    }

    /** @return the reason the send must be refused, or null when it may proceed. */
    String suppressionReason(String subjectType, UUID subjectId, String channel, String purpose) {
        List<ConsentPosition> positions = positions(history(subjectType, subjectId));
        ConsentPosition exact = positions.stream()
                .filter(p -> p.channel().equals(channel) && p.purpose().equals(purpose))
                .findFirst().orElse(null);
        ConsentPosition allChannels = positions.stream()
                .filter(p -> "ANY".equals(p.channel()) && p.purpose().equals(purpose))
                .findFirst().orElse(null);

        if (exact != null && exact.suppressed()) {
            return "This person's " + channel.toLowerCase(Locale.ROOT) + " consent for "
                   + purpose.toLowerCase(Locale.ROOT).replace('_', ' ') + " is "
                   + exact.state().toLowerCase(Locale.ROOT) + " as of " + exact.effectiveSince() + ".";
        }
        if (allChannels != null && allChannels.suppressed()) {
            return "This person has withdrawn consent across all channels for "
                   + purpose.toLowerCase(Locale.ROOT).replace('_', ' ')
                   + " as of " + allChannels.effectiveSince() + ".";
        }
        if ("EMAIL".equals(channel) && emailBounced(subjectType, subjectId)) {
            return "This person's email address is marked as bouncing. Continuing to send damages "
                   + "deliverability for every other recipient on the domain.";
        }
        if (exact == null && allChannels == null && OPT_IN_REQUIRED.contains(purpose)) {
            return purpose.toLowerCase(Locale.ROOT).replace('_', ' ')
                   + " needs a recorded opt-in and none exists for this person on "
                   + channel.toLowerCase(Locale.ROOT) + ".";
        }
        return null;
    }

    private boolean emailBounced(String subjectType, UUID subjectId) {
        if (!"CONTACT".equals(subjectType)) return false;
        Boolean bounced = jdbc.query("""
                select email_bounced from crm.contact
                where tenant_id = ? and id = ? and deleted_at is null
                """, rs -> rs.next() && rs.getBoolean(1), TenantContext.get().tenantId(), subjectId);
        return Boolean.TRUE.equals(bounced);
    }

    /** Recent send/dial attempts for the consent panel. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentAttempts(String subjectType, UUID subjectId, int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        jdbc.query("""
                select channel, purpose, origin, outcome, block_reason, subject_line, attempted_at
                from crm.outreach_attempt
                where tenant_id = ? and subject_type = ? and subject_id = ?
                order by attempted_at desc
                limit ?
                """, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("channel", rs.getString("channel"));
                    row.put("purpose", rs.getString("purpose"));
                    row.put("origin", rs.getString("origin"));
                    row.put("outcome", rs.getString("outcome"));
                    row.put("blockReason", rs.getString("block_reason"));
                    row.put("subjectLine", rs.getString("subject_line"));
                    row.put("attemptedAt", rs.getTimestamp("attempted_at").toInstant().toString());
                    rows.add(row);
                }, TenantContext.get().tenantId(), AccountService.upper(subjectType), subjectId,
                Math.max(1, Math.min(limit, 100)));
        return rows;
    }
}
