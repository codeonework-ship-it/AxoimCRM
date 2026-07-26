package com.axiom.locking;

import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Edit locks: one person works on a record at a time (V339).
 *
 * <h2>What this adds over the version column</h2>
 * Optimistic versioning already stops two saves from overwriting each other. It
 * does nothing about two people spending ten minutes each writing the same field
 * and discovering it at save time, when one of them has to throw their work away.
 * This tells the second person before they start typing.
 *
 * <h2>The lease, and the one rule that matters</h2>
 * A lock is a LEASE with an expiry, renewed by a heartbeat while the form is open.
 * The rule that follows from that, and the one this class exists to enforce
 * consistently:
 *
 * <p><b>A row in {@code crm.record_lock} is not a held lock. A row whose
 * {@code expires_at} is in the future is a held lock.</b>
 *
 * <p>Every read here compares against {@code now()}. Code that treats row
 * presence as the lock is how records become permanently unlockable — the holder
 * closes their laptop, no release ever arrives, and an administrator has to go
 * into the database. The expiry means that situation resolves itself in one TTL.
 *
 * <h2>Why the timings are what they are</h2>
 * {@link #TTL} two minutes, heartbeat every thirty seconds. Four heartbeats per
 * lease, so three consecutive failures — a flaky connection, a backgrounded tab, a
 * brief deploy — can be absorbed without dropping a lock somebody is actively
 * using. And the worst case for a colleague is a two-minute wait after the holder
 * has genuinely gone, which is short enough not to need an override in practice.
 */
@Service
public class RecordLockService {

    /** The lease length. See the class comment for why two minutes. */
    public static final Duration TTL = Duration.ofMinutes(2);

    /**
     * What the client should be told to use. Sent to the client rather than
     * hardcoded there, so the two can never drift into a client that heartbeats
     * slower than the server expires.
     */
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    /**
     * Object types that may be locked. An allow-list rather than free text: the
     * column is used to key locks, so a caller inventing "Account" alongside
     * "ACCOUNT" would produce two independent locks on the same record and the
     * feature would silently stop working for exactly the records people fight
     * over. Matches the vocabulary in activity.user_activity and the bulk
     * allow-list.
     */
    private static final List<String> LOCKABLE = List.of(
            "ACCOUNT", "CONTACT", "LEAD", "OPPORTUNITY", "QUOTE", "ACTIVITY",
            "SALES_ORDER", "PURCHASE_ORDER", "INVOICE", "VENDOR", "PRODUCT",
            "PRICE_BOOK", "CAMPAIGN", "CASE", "CONTRACT", "REPORT_DEFINITION");

    private final JdbcTemplate jdbc;

    public RecordLockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A lock as the client sees it.
     *
     * @param heldByMe   whether the caller is the holder — the client needs this
     *                   to decide between "you are editing" and "someone else is",
     *                   and computing it here means the client never has to
     *                   compare user ids itself
     * @param expiresAt  when the lease lapses if the holder stops heartbeating
     */
    public record Lock(String objectType, UUID recordId,
                       UUID holderId, String holderEmail, String holderName,
                       Instant acquiredAt, Instant expiresAt,
                       boolean heldByMe, long heartbeatSeconds) {}

    /**
     * Take the lock, or refuse with a 409 that says who has it.
     *
     * <p>Re-acquiring a lock you already hold succeeds and extends it. That is not
     * a special case to be tidied away — it is the normal path when someone
     * reopens a form, reloads the tab, or opens the same record in a second tab,
     * and refusing it would lock a user out of their own edit.
     */
    @Transactional
    public Lock acquire(String rawType, UUID recordId) {
        String objectType = normalise(rawType);
        UUID tenantId = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();

        /*
         * The whole decision happens in one statement, and it has to.
         *
         * A read-then-write version of this ("is it locked? no? then insert")
         * has a race exactly the width of the gap between the two queries, and
         * two users clicking Edit at the same moment is precisely the scenario
         * being defended against — the race is not theoretical here, it is the
         * feature's main use case. So the guard lives in the ON CONFLICT
         * predicate, where the database evaluates it while holding the row lock:
         * the update applies only if the existing lease has expired or is mine.
         *
         * When the predicate fails, no row is updated, and rowsAffected == 0 tells
         * us someone else holds it — without a second query having to re-derive
         * that and possibly disagree.
         */
        int applied = jdbc.update("""
                insert into crm.record_lock (tenant_id, object_type, record_id,
                        holder_id, holder_email, holder_name,
                        acquired_at, heartbeat_at, expires_at)
                values (?, ?, ?, ?, ?, ?, now(), now(), now() + (? * interval '1 second'))
                on conflict (tenant_id, object_type, record_id) do update
                   set holder_id    = excluded.holder_id,
                       holder_email = excluded.holder_email,
                       holder_name  = excluded.holder_name,
                       heartbeat_at = now(),
                       expires_at   = excluded.expires_at,
                       -- Only reset acquired_at when the holder actually changes,
                       -- so "editing since" survives a re-acquire by the same user.
                       acquired_at  = case when crm.record_lock.holder_id = excluded.holder_id
                                           then crm.record_lock.acquired_at else now() end,
                       stolen_from  = case when crm.record_lock.holder_id <> excluded.holder_id
                                           then crm.record_lock.holder_id else null end,
                       stolen_at    = case when crm.record_lock.holder_id <> excluded.holder_id
                                           then now() else null end
                 where crm.record_lock.expires_at <= now()
                    or crm.record_lock.holder_id = excluded.holder_id
                """,
                tenantId, objectType, recordId,
                me, TenantContext.get().email(), TenantContext.get().displayName(),
                TTL.toSeconds());

        if (applied == 0) {
            Lock held = read(tenantId, objectType, recordId, me);
            if (held == null) {
                /*
                 * The conflicting row vanished between the upsert and this read —
                 * the holder released it. Nothing is wrong and the caller wanted
                 * the lock, so try once more rather than reporting a conflict that
                 * no longer exists. One retry only: a genuine contender will be
                 * reported on the second pass.
                 */
                return acquire(objectType, recordId);
            }
            throw new ConflictException(conflictMessage(held));
        }

        Lock mine = read(tenantId, objectType, recordId, me);
        if (mine == null) {
            // Cannot happen after a successful upsert in the same transaction, but
            // returning null here would surface as an NPE in a controller rather
            // than as something diagnosable.
            throw new IllegalStateException(
                    "Lock on " + objectType + " " + recordId + " was written but could not be read back.");
        }
        return mine;
    }

    /**
     * Extend a lease the caller already holds.
     *
     * <p>Refuses rather than silently acquiring if the caller is not the holder. A
     * heartbeat that can take a lock is not a heartbeat — it would let a client
     * that missed the conflict on open quietly steal the record from whoever
     * legitimately holds it, at the next 30-second tick.
     */
    @Transactional
    public Lock heartbeat(String rawType, UUID recordId) {
        String objectType = normalise(rawType);
        UUID tenantId = TenantContext.get().tenantId();
        UUID me = TenantContext.get().userId();

        int applied = jdbc.update("""
                update crm.record_lock
                   set heartbeat_at = now(),
                       expires_at   = now() + (? * interval '1 second')
                 where tenant_id = ? and object_type = ? and record_id = ?
                   and holder_id = ? and expires_at > now()
                """, TTL.toSeconds(), tenantId, objectType, recordId, me);

        if (applied == 0) {
            Lock held = read(tenantId, objectType, recordId, me);
            if (held == null) {
                throw new ConflictException("Your edit lock on this record has expired. "
                        + "Reopen the record to continue — any unsaved changes are still in your form.");
            }
            throw new ConflictException(conflictMessage(held));
        }
        return read(tenantId, objectType, recordId, me);
    }

    /**
     * Release a lock. Idempotent: releasing something you do not hold is a no-op
     * rather than an error, because this is called from page-unload paths where
     * the caller genuinely cannot know whether the lease already lapsed, and an
     * error there would be noise nobody can act on.
     */
    @Transactional
    public void release(String rawType, UUID recordId) {
        String objectType = normalise(rawType);
        jdbc.update("""
                delete from crm.record_lock
                 where tenant_id = ? and object_type = ? and record_id = ? and holder_id = ?
                """,
                TenantContext.get().tenantId(), objectType, recordId,
                TenantContext.get().userId());
    }

    /** Every lock this user currently holds — used to release them all on sign-out. */
    @Transactional
    public int releaseAllForCurrentUser() {
        return jdbc.update("""
                delete from crm.record_lock where tenant_id = ? and holder_id = ?
                """, TenantContext.get().tenantId(), TenantContext.get().userId());
    }

    /**
     * The lock state for a record, or null if it is free.
     *
     * <p>Read-only and safe to poll: this is what a form calls on open to decide
     * whether to render editable fields or a "locked by" banner.
     */
    @Transactional(readOnly = true)
    public Lock status(String rawType, UUID recordId) {
        return read(TenantContext.get().tenantId(), normalise(rawType), recordId,
                TenantContext.get().userId());
    }

    /**
     * Force a lock open. Administrators only.
     *
     * <p>The lease expiry means this is not needed for the ordinary "they went
     * home" case, which is the situation that usually drives people to build an
     * override and then use it constantly. It exists for the two-minute window
     * where somebody genuinely needs a record now, and it is deliberately a
     * separate, role-gated call so that using it is a visible act rather than an
     * indistinguishable acquire.
     */
    @Transactional
    public void forceRelease(String rawType, UUID recordId) {
        String role = TenantContext.get().role();
        if (!"TENANT_ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
            throw new ForbiddenException(
                    "Only a workspace administrator can force an edit lock open. "
                    + "Locks lapse on their own after " + TTL.toMinutes() + " minutes without activity.");
        }
        jdbc.update("delete from crm.record_lock where tenant_id = ? and object_type = ? and record_id = ?",
                TenantContext.get().tenantId(), normalise(rawType), recordId);
    }

    /**
     * Delete lapsed rows. Not required for correctness — every read already
     * ignores expired rows — so this is housekeeping, and it is safe to run at any
     * time from anywhere.
     */
    @Transactional
    public int sweepExpired() {
        return jdbc.update("delete from crm.record_lock where expires_at <= now() - interval '1 hour'");
    }

    // ------------------------------------------------------------------ internals

    /** Returns the LIVE lock only. An expired row reads as free. */
    private Lock read(UUID tenantId, String objectType, UUID recordId, UUID me) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select object_type, record_id, holder_id, holder_email, holder_name,
                       acquired_at, expires_at
                  from crm.record_lock
                 where tenant_id = ? and object_type = ? and record_id = ?
                   and expires_at > now()
                """, tenantId, objectType, recordId);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        UUID holderId = (UUID) row.get("holder_id");
        return new Lock(
                (String) row.get("object_type"),
                (UUID) row.get("record_id"),
                holderId,
                (String) row.get("holder_email"),
                (String) row.get("holder_name"),
                ((java.sql.Timestamp) row.get("acquired_at")).toInstant(),
                ((java.sql.Timestamp) row.get("expires_at")).toInstant(),
                holderId.equals(me),
                HEARTBEAT_INTERVAL.toSeconds());
    }

    /**
     * The refusal a user actually reads. It names the person, because "record is
     * locked" leaves the reader with no next action, whereas a name gives them one
     * — go and ask them. It also says when the lock lapses, so waiting is a
     * decision they can make rather than an unknown.
     */
    private static String conflictMessage(Lock held) {
        String who = held.holderName() != null && !held.holderName().isBlank()
                ? held.holderName() + " (" + held.holderEmail() + ")"
                : held.holderEmail();
        long secondsLeft = Math.max(0, Duration.between(Instant.now(), held.expiresAt()).getSeconds());
        return who + " is editing this record. It unlocks automatically in about "
                + Math.max(1, (secondsLeft + 59) / 60) + " minute(s) if they stop working on it, "
                + "or sooner if they close it. Your view is up to date and safe to read.";
    }

    /**
     * Upper-cased and checked against the allow-list. Rejecting an unknown type
     * rather than accepting it is the point: an accepted-but-unrecognised type
     * would create locks nothing else ever looks for, so the feature would appear
     * to work while protecting nothing.
     */
    static String normalise(String rawType) {
        String candidate = rawType == null ? "" : rawType.trim().toUpperCase(Locale.ROOT);
        if (!LOCKABLE.contains(candidate)) {
            throw new ConflictException("'" + rawType + "' is not a lockable record type. "
                    + "Lockable types: " + String.join(", ", LOCKABLE) + ".");
        }
        return candidate;
    }
}
