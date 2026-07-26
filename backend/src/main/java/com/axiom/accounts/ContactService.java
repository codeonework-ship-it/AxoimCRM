package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FR-ACC-002 — contact record with multiple typed addresses and multiple typed
 * communication channels, and a primary account association.
 *
 * <p>"The primary of each type is unambiguous" is enforced by partial unique
 * indexes in V40, not by application care. Promoting a channel to primary
 * therefore demotes the incumbent in the same transaction; the alternative — two
 * primaries and a UI that picks one — is how a cadence ends up dialling the
 * switchboard.
 */
@Service
public class ContactService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final DuplicateService duplicates;
    private final ActorSession actor;

    public ContactService(JdbcTemplate jdbc, AuditService audit,
                          DuplicateService duplicates, ActorSession actor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.duplicates = duplicates;
        this.actor = actor;
    }

    // --------------------------------------------------------------- contracts

    public record ContactRequest(
            @NotBlank(message = "First name is required") @Size(max = 120) String firstName,
            @NotBlank(message = "Last name is required") @Size(max = 120) String lastName,
            UUID accountId, String title, String department, String seniority,
            UUID reportsToContactId, UUID ownerId, String email, String phone, String mobile,
            String status, String sourceSystem, String externalRef,
            boolean acknowledgeDuplicates, String duplicateReason) {}

    public record ContactDetail(UUID id, UUID accountId, String accountName, String firstName,
                                String lastName, String title, String department, String seniority,
                                UUID reportsToContactId, String reportsToName, UUID ownerId,
                                String ownerName, String email, String phone, String mobile,
                                String status, boolean emailBounced, Instant lastEngagedAt,
                                String sourceSystem, String externalRef, UUID mergedIntoId,
                                Instant createdAt, Instant updatedAt, long version) {}

    public record AddressRequest(@NotBlank String ownerEntity, UUID ownerId,
                                 @NotBlank String addressType, boolean isPrimary,
                                 @NotBlank String line1, String line2, String city,
                                 String stateRegion, String postalCode, String countryCode) {}

    public record AddressRow(UUID id, String ownerEntity, UUID ownerId, String addressType,
                             boolean isPrimary, String line1, String line2, String city,
                             String stateRegion, String postalCode, String countryCode,
                             String validationStatus) {}

    public record ChannelRequest(@NotBlank String channel, @NotBlank String channelType,
                                 @NotBlank String value, boolean isPrimary) {}

    public record ChannelRow(UUID id, UUID contactId, String channel, String channelType,
                             String value, boolean isPrimary, Instant verifiedAt) {}

    /** One contact plus every related list the detail view renders beside it. */
    public record ContactView(ContactDetail contact, List<ContactDetail> directReports,
                              List<RelatedActivity> timeline, List<AddressRow> addresses,
                              List<ChannelRow> channels) {}

    public record RelatedActivity(UUID id, String activityType, String subject, String status,
                                  String ownerName, Instant occurredAt, Instant dueAt) {}

    public record ReassignRequest(UUID ownerId, @Size(max = 500) String reason) {}

    public record DeleteRequest(@Size(max = 500) String reason) {}

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public ContactDetail get(UUID id) {
        try {
            return jdbc.queryForObject("""
                    select c.id, c.account_id, a.name as account_name, c.first_name, c.last_name,
                           c.title, c.department, c.seniority, c.reports_to_contact_id,
                           trim(coalesce(m.first_name, '') || ' ' || coalesce(m.last_name, '')) as reports_to_name,
                           c.owner_id, u.display_name as owner_name, c.email, c.phone, c.mobile,
                           c.status, c.email_bounced, c.last_engaged_at, c.source_system,
                           c.external_ref, c.merged_into_id, c.created_at, c.updated_at, c.version
                    from crm.contact c
                    left join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
                    left join crm.contact m on m.tenant_id = c.tenant_id and m.id = c.reports_to_contact_id
                    left join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
                    where c.tenant_id = ? and c.id = ? and c.deleted_at is null
                    """, (rs, i) -> new ContactDetail(
                    rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                    rs.getString("account_name"), rs.getString("first_name"), rs.getString("last_name"),
                    rs.getString("title"), rs.getString("department"), rs.getString("seniority"),
                    rs.getObject("reports_to_contact_id", UUID.class),
                    blankToNull(rs.getString("reports_to_name")),
                    rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                    rs.getString("email"), rs.getString("phone"), rs.getString("mobile"),
                    rs.getString("status"), rs.getBoolean("email_bounced"),
                    rs.getTimestamp("last_engaged_at") == null ? null : rs.getTimestamp("last_engaged_at").toInstant(),
                    rs.getString("source_system"), rs.getString("external_ref"),
                    rs.getObject("merged_into_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(), rs.getLong("version")),
                    TenantContext.get().tenantId(), id);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Contact not found, or it has been merged away or deleted");
        }
    }

    /**
     * The list behind the Contacts grid.
     *
     * <p>Search spans name, email and title in one predicate. Restricting it to
     * the name would make the box useless for the lookup people actually perform
     * — pasting an email address from a thread they are replying to.
     */
    @Transactional(readOnly = true)
    public List<ContactDetail> list(UUID accountId, String search, String status) {
        StringBuilder sql = new StringBuilder(LIST_SELECT)
                .append(" where c.tenant_id = ? and c.deleted_at is null and c.merged_into_id is null");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        if (accountId != null) {
            sql.append(" and c.account_id = ?");
            args.add(accountId);
        }
        String needle = AccountService.blankToNull(search);
        if (needle != null) {
            sql.append(" and (c.first_name ilike ? or c.last_name ilike ? or c.email ilike ? or c.title ilike ?)");
            String like = "%" + needle + "%";
            args.add(like); args.add(like); args.add(like); args.add(like);
        }
        String state = AccountService.upper(status);
        if (state != null) {
            sql.append(" and c.status = ?");
            args.add(state);
        }
        sql.append(" order by c.last_name, c.first_name");
        return jdbc.query(sql.toString(), (rs, i) -> mapDetail(rs), args.toArray());
    }

    /**
     * Everything the detail view shows, assembled in one call.
     *
     * <p>Four round trips from the browser would paint the drawer in four stages
     * — which reads as broken even when every request succeeds — and, worse, four
     * independent reads can disagree about what the record looked like. One
     * read-only transaction gives the drawer a single consistent snapshot.
     */
    @Transactional(readOnly = true)
    public ContactView view(UUID id) {
        ContactDetail contact = get(id);
        UUID tenant = TenantContext.get().tenantId();

        List<ContactDetail> reports = jdbc.query(
                LIST_SELECT + " where c.tenant_id = ? and c.reports_to_contact_id = ?"
                        + " and c.deleted_at is null order by c.last_name, c.first_name",
                (rs, i) -> mapDetail(rs), tenant, id);

        /*
         * Two ways an activity reaches a contact, and the timeline has to honour
         * both or it under-reports. `related_entity_*` is the activity's primary
         * subject; `engagement.activity_relation` is the many-to-many that records
         * everyone else involved — the contact who was CC'd on the email, the
         * second attendee on the call. Showing only the first would tell a user
         * "no engagement" about someone who was on every meeting.
         *
         * DISTINCT because a contact can legitimately be both: the primary subject
         * of a call and also listed as a participant on it.
         */
        /*
         * EXISTS rather than a join, and therefore no DISTINCT. Joining the
         * relation table multiplies a row per participant link, which then needs
         * DISTINCT to collapse — and `SELECT DISTINCT` forbids ordering by
         * coalesce(occurred_at, due_at) unless that expression is also selected,
         * so the shape fights itself. A semi-join asks the only question that
         * matters ("is this contact on this activity at all") and returns each
         * activity exactly once.
         */
        List<RelatedActivity> timeline = jdbc.query("""
                select act.id, act.activity_type, act.subject, act.status,
                       u.display_name as owner_name, act.occurred_at, act.due_at
                from engagement.activity act
                left join identity.app_user u on u.tenant_id = act.tenant_id and u.id = act.owner_id
                where act.tenant_id = ? and act.deleted_at is null
                  and (
                        (act.related_entity_type = 'CONTACT' and act.related_entity_id = ?)
                     or exists (
                          select 1 from engagement.activity_relation rel
                          where rel.tenant_id = act.tenant_id and rel.activity_id = act.id
                            and rel.related_type = 'CONTACT' and rel.related_id = ?
                        )
                  )
                order by coalesce(act.occurred_at, act.due_at) desc nulls last
                limit 100
                """, (rs, i) -> new RelatedActivity(rs.getObject("id", UUID.class),
                rs.getString("activity_type"), rs.getString("subject"), rs.getString("status"),
                rs.getString("owner_name"), ts(rs.getTimestamp("occurred_at")), ts(rs.getTimestamp("due_at"))),
                tenant, id, id);

        List<AddressRow> postal = addresses("CONTACT", id);
        List<ChannelRow> comms = channels(id);
        return new ContactView(contact, reports, timeline, postal, comms);
    }

    private static final String LIST_SELECT = """
            select c.id, c.account_id, a.name as account_name, c.first_name, c.last_name,
                   c.title, c.department, c.seniority, c.reports_to_contact_id,
                   trim(coalesce(m.first_name, '') || ' ' || coalesce(m.last_name, '')) as reports_to_name,
                   c.owner_id, u.display_name as owner_name, c.email, c.phone, c.mobile,
                   c.status, c.email_bounced, c.last_engaged_at, c.source_system,
                   c.external_ref, c.merged_into_id, c.created_at, c.updated_at, c.version
            from crm.contact c
            left join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
            left join crm.contact m on m.tenant_id = c.tenant_id and m.id = c.reports_to_contact_id
            left join identity.app_user u on u.tenant_id = c.tenant_id and u.id = c.owner_id
            """;

    private ContactDetail mapDetail(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ContactDetail(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("account_name"), rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("title"), rs.getString("department"), rs.getString("seniority"),
                rs.getObject("reports_to_contact_id", UUID.class),
                blankToNull(rs.getString("reports_to_name")),
                rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("mobile"),
                rs.getString("status"), rs.getBoolean("email_bounced"),
                ts(rs.getTimestamp("last_engaged_at")), rs.getString("source_system"),
                rs.getString("external_ref"), rs.getObject("merged_into_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }

    private static Instant ts(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    // ------------------------------------------------------------------ writing

    @Transactional
    public ContactDetail create(ContactRequest request) {
        actor.bind();
        String first = AccountService.require(request.firstName(), "First name is required");
        String last = AccountService.require(request.lastName(), "Last name is required");
        String fullName = first + " " + last;

        DuplicateService.Assessment assessment = duplicates.assess(new DuplicateService.Probe(
                "CONTACT", fullName, null, request.email(),
                request.phone() != null ? request.phone() : request.mobile(), null, null));
        guard(assessment, null, "CREATE", request.acknowledgeDuplicates(), request.duplicateReason(), fullName);

        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into crm.contact
                      (tenant_id, account_id, first_name, last_name, title, department, seniority,
                       reports_to_contact_id, owner_id, email, phone, mobile,
                       status, source_system, external_ref, created_by, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, 'ACTIVE'), ?, ?, ?, ?)
                    returning id
                    """, UUID.class, TenantContext.get().tenantId(), request.accountId(), first, last,
                    AccountService.blankToNull(request.title()), AccountService.blankToNull(request.department()),
                    AccountService.upper(request.seniority()), request.reportsToContactId(),
                    request.ownerId() == null ? TenantContext.get().userId() : request.ownerId(),
                    AccountService.blankToNull(request.email()), AccountService.blankToNull(request.phone()),
                    AccountService.blankToNull(request.mobile()), AccountService.upper(request.status()),
                    AccountService.blankToNull(request.sourceSystem()),
                    AccountService.blankToNull(request.externalRef()),
                    TenantContext.get().userId(), TenantContext.get().userId());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(AccountService.databaseMessage(ex,
                    "That contact could not be saved. Check the account, manager and email values."));
        }
        if (assessment.warned()) {
            duplicates.recordDecision("CONTACT", id, "CREATE", "PROCEEDED", assessment, request.duplicateReason());
        }
        audit.record("CONTACT_CREATE", "CONTACT", id, "Created contact " + fullName,
                Map.of("name", fullName, "accountId", String.valueOf(request.accountId()),
                        "duplicateTopConfidence", assessment.topConfidence()));
        return get(id);
    }

    @Transactional
    public ContactDetail update(UUID id, long expectedVersion, ContactRequest request) {
        actor.bind();
        ContactDetail before = get(id);
        String first = AccountService.require(request.firstName(), "First name is required");
        String last = AccountService.require(request.lastName(), "Last name is required");
        String fullName = first + " " + last;

        DuplicateService.Assessment assessment = duplicates.assess(new DuplicateService.Probe(
                "CONTACT", fullName, null, request.email(),
                request.phone() != null ? request.phone() : request.mobile(), null, id));
        guard(assessment, id, "UPDATE", request.acknowledgeDuplicates(), request.duplicateReason(), fullName);
        assertNoReportingCycle(id, request.reportsToContactId());

        int updated;
        try {
            updated = jdbc.update("""
                    update crm.contact
                    set account_id = ?, first_name = ?, last_name = ?, title = ?, department = ?,
                        seniority = ?, reports_to_contact_id = ?, owner_id = coalesce(?, owner_id),
                        email = ?, phone = ?, mobile = ?, status = coalesce(?, status),
                        source_system = ?, external_ref = ?, updated_at = now(), updated_by = ?,
                        version = version + 1
                    where tenant_id = ? and id = ? and deleted_at is null and version = ?
                    """, request.accountId(), first, last, AccountService.blankToNull(request.title()),
                    AccountService.blankToNull(request.department()), AccountService.upper(request.seniority()),
                    request.reportsToContactId(), request.ownerId(),
                    AccountService.blankToNull(request.email()), AccountService.blankToNull(request.phone()),
                    AccountService.blankToNull(request.mobile()), AccountService.upper(request.status()),
                    AccountService.blankToNull(request.sourceSystem()),
                    AccountService.blankToNull(request.externalRef()), TenantContext.get().userId(),
                    TenantContext.get().tenantId(), id, expectedVersion);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(AccountService.databaseMessage(ex,
                    "That contact could not be saved. A contact cannot report to itself and its "
                    + "manager must belong to the same tenant."));
        }
        if (updated == 0) {
            throw new ConflictException("This contact changed while you were editing it (you had version "
                    + expectedVersion + ", the stored record is version " + before.version()
                    + "). Reload the contact and re-apply your changes.");
        }
        if (assessment.warned()) {
            duplicates.recordDecision("CONTACT", id, "UPDATE", "PROCEEDED", assessment, request.duplicateReason());
        }
        audit.record("CONTACT_UPDATE", "CONTACT", id, "Updated contact " + fullName,
                Map.of("name", fullName, "fromVersion", expectedVersion));
        return get(id);
    }

    /**
     * Rejects a reporting line that would loop, before the database has to.
     *
     * <p>V40 already has a {@code contact_not_own_manager} CHECK, and it is the
     * real guarantee — this method is not a second opinion, it is a translation
     * layer. Left to the database, the API returned
     * {@code violates check constraint "contact_not_own_manager"}, which names an
     * internal object and tells the person editing the record nothing they can
     * act on. A CHECK constraint has no room for prose; a service does.
     *
     * <p>It also covers what the CHECK cannot: a longer cycle. A single-row CHECK
     * can see only that row, so A→B→A passes it. The recursive walk catches that
     * and names the contact closing the loop.
     */
    private void assertNoReportingCycle(UUID contactId, UUID proposedManagerId) {
        if (proposedManagerId == null) return;
        if (proposedManagerId.equals(contactId)) {
            throw new ConflictException("A contact cannot report to themselves. Pick a different manager, "
                    + "or leave the reporting line empty.");
        }
        List<Map<String, Object>> loop = jdbc.queryForList("""
                with recursive chain as (
                  select c.id, trim(coalesce(c.first_name,'') || ' ' || coalesce(c.last_name,'')) as name,
                         c.reports_to_contact_id, 1 as depth
                  from crm.contact c
                  where c.tenant_id = ? and c.id = ? and c.deleted_at is null
                  union all
                  select c.id, trim(coalesce(c.first_name,'') || ' ' || coalesce(c.last_name,'')),
                         c.reports_to_contact_id, chain.depth + 1
                  from crm.contact c
                  join chain on chain.reports_to_contact_id = c.id
                  where c.tenant_id = ? and c.deleted_at is null and chain.depth < 50
                )
                select name from chain where id = ?
                """, TenantContext.get().tenantId(), proposedManagerId,
                TenantContext.get().tenantId(), contactId);
        if (!loop.isEmpty()) {
            throw new ConflictException("That reporting line is circular: " + loop.get(0).get("name")
                    + " already reports up to this contact. Pick a manager outside this branch.");
        }
    }

    /**
     * Clone. Carries over everything expensive to retype and deliberately drops
     * the three things that identify the original: email, and the source-system
     * and external-reference provenance.
     *
     * <p>Copying the email would produce a record the duplicate engine considers
     * the same person — so the clone would either be blocked by {@link #guard} or,
     * if acknowledged, create exactly the duplicate that guard exists to prevent.
     * Provenance is dropped for the same reason in a different register: a cloned
     * record did not come from the source system, and claiming it did would put a
     * false lineage into every downstream export.
     */
    @Transactional
    public ContactDetail clone(UUID id, ContactRequest overrides) {
        ContactDetail source = get(id);
        ContactRequest request = new ContactRequest(
                pick(overrides == null ? null : overrides.firstName(), source.firstName()),
                pick(overrides == null ? null : overrides.lastName(), source.lastName()),
                overrides != null && overrides.accountId() != null ? overrides.accountId() : source.accountId(),
                pick(overrides == null ? null : overrides.title(), source.title()),
                pick(overrides == null ? null : overrides.department(), source.department()),
                pick(overrides == null ? null : overrides.seniority(), source.seniority()),
                overrides != null && overrides.reportsToContactId() != null
                        ? overrides.reportsToContactId() : source.reportsToContactId(),
                overrides != null && overrides.ownerId() != null ? overrides.ownerId() : source.ownerId(),
                overrides == null ? null : AccountService.blankToNull(overrides.email()),
                pick(overrides == null ? null : overrides.phone(), source.phone()),
                pick(overrides == null ? null : overrides.mobile(), source.mobile()),
                pick(overrides == null ? null : overrides.status(), source.status()),
                null, null,
                /*
                 * A clone acknowledges the duplicate, always. It is by definition a
                 * near-copy of a record the user is looking at, so the fuzzy-name
                 * rule will match the source every time — leaving this false made
                 * Clone return 409 on the first click, every time, for every
                 * contact. Acknowledging is not suppressing: create() still calls
                 * recordDecision, so the decision and its reason land in the
                 * duplicate log exactly as a hand-acknowledged one would.
                 */
                true,
                pick(overrides == null ? null : overrides.duplicateReason(),
                        "Cloned from contact " + id));

        ContactDetail created = create(request);
        audit.record("CONTACT_CLONE", "CONTACT", created.id(),
                "Cloned " + source.firstName() + " " + source.lastName()
                        + " into " + created.firstName() + " " + created.lastName(),
                Map.of("sourceContactId", source.id().toString(),
                        "emailCopied", "false", "provenanceCopied", "false"));
        return created;
    }

    /** Ownership transfer for one contact. The bulk path reuses this method. */
    @Transactional
    public ContactDetail reassign(UUID id, UUID ownerId, String reason) {
        actor.bind();
        ContactDetail before = get(id);
        if (ownerId == null) {
            throw new IllegalArgumentException("A new owner is required to transfer a contact");
        }
        Long owner = jdbc.queryForObject(
                "select count(*) from identity.app_user where tenant_id = ? and id = ? and active",
                Long.class, TenantContext.get().tenantId(), ownerId);
        if (owner == null || owner == 0) {
            throw new NotFoundException("That owner is not an active user in this workspace");
        }
        jdbc.update("""
                update crm.contact set owner_id = ?, updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null
                """, ownerId, TenantContext.get().userId(), TenantContext.get().tenantId(), id);
        audit.record("CONTACT_REASSIGN", "CONTACT", id,
                "Transferred " + before.firstName() + " " + before.lastName() + " to a new owner",
                Map.of("fromOwnerId", String.valueOf(before.ownerId()), "toOwnerId", ownerId.toString(),
                        "reason", reason == null || reason.isBlank() ? "not stated" : reason.trim()));
        return get(id);
    }

    /**
     * Soft delete, refused while anyone still reports to this contact.
     *
     * <p>The refusal is the point. Deleting a manager row would leave every direct
     * report pointing at a record that no longer resolves, and the org chart would
     * silently lose a branch rather than report a problem. Naming the count tells
     * the caller exactly how much work clearing the way involves.
     */
    @Transactional
    public void delete(UUID id, String reason) {
        actor.bind();
        ContactDetail before = get(id);
        Long reports = jdbc.queryForObject("""
                select count(*) from crm.contact
                where tenant_id = ? and reports_to_contact_id = ? and deleted_at is null
                """, Long.class, TenantContext.get().tenantId(), id);
        if (reports != null && reports > 0) {
            throw new ConflictException(before.firstName() + " " + before.lastName()
                    + " is the reporting manager for " + reports + " other contact(s). Reassign or clear "
                    + "their reporting line first, so the org chart does not point at a deleted record.");
        }
        jdbc.update("""
                update crm.contact
                set deleted_at = now(), deleted_by = ?, updated_at = now(), updated_by = ?,
                    version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null
                """, TenantContext.get().userId(), TenantContext.get().userId(),
                TenantContext.get().tenantId(), id);
        audit.record("CONTACT_DELETE", "CONTACT", id,
                "Deleted contact " + before.firstName() + " " + before.lastName(),
                Map.of("reason", reason == null || reason.isBlank() ? "not stated" : reason.trim()));
    }

    private static String pick(String preferred, String fallback) {
        String chosen = AccountService.blankToNull(preferred);
        return chosen != null ? chosen : AccountService.blankToNull(fallback);
    }

    private void guard(DuplicateService.Assessment assessment, UUID entityId, String operation,
                       boolean acknowledged, String reason, String label) {
        if (assessment.blocked()) {
            duplicates.recordDecision("CONTACT", entityId, operation, "BLOCKED", assessment, reason);
            throw new DuplicateBlockedException("\"" + label + "\" matches an existing record on a blocking "
                    + "duplicate rule (" + String.join(", ", assessment.blockingRuleCodes())
                    + "). Merge into the existing record instead of creating a second one.", assessment);
        }
        if (assessment.warned() && !acknowledged) {
            throw new DuplicateBlockedException("\"" + label + "\" closely matches "
                    + assessment.candidates().size() + " existing record(s). Review them before continuing.",
                    assessment);
        }
    }

    // ---------------------------------------------------------------- addresses

    @Transactional(readOnly = true)
    public List<AddressRow> addresses(String ownerEntity, UUID ownerId) {
        return jdbc.query("""
                select id, owner_entity, owner_id, address_type, is_primary, line1, line2, city,
                       state_region, postal_code, country_code, validation_status
                from crm.postal_address
                where tenant_id = ? and owner_entity = ? and owner_id = ? and deleted_at is null
                order by address_type, is_primary desc
                """, (rs, i) -> new AddressRow(rs.getObject("id", UUID.class),
                rs.getString("owner_entity"), rs.getObject("owner_id", UUID.class),
                rs.getString("address_type"), rs.getBoolean("is_primary"), rs.getString("line1"),
                rs.getString("line2"), rs.getString("city"), rs.getString("state_region"),
                rs.getString("postal_code"), rs.getString("country_code"), rs.getString("validation_status")),
                TenantContext.get().tenantId(), AccountService.upper(ownerEntity), ownerId);
    }

    @Transactional
    public AddressRow addAddress(AddressRequest request) {
        actor.bind();
        String ownerEntity = AccountService.upper(request.ownerEntity());
        String type = AccountService.upper(request.addressType());
        if (request.isPrimary()) demotePrimaryAddress(ownerEntity, request.ownerId(), type);
        UUID id = jdbc.queryForObject("""
                insert into crm.postal_address
                  (tenant_id, owner_entity, owner_id, address_type, is_primary, line1, line2,
                   city, state_region, postal_code, country_code, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, TenantContext.get().tenantId(), ownerEntity, request.ownerId(), type,
                request.isPrimary(), AccountService.require(request.line1(), "Address line 1 is required"),
                AccountService.blankToNull(request.line2()), AccountService.blankToNull(request.city()),
                AccountService.blankToNull(request.stateRegion()), AccountService.blankToNull(request.postalCode()),
                AccountService.upper(request.countryCode()), TenantContext.get().userId());
        audit.record("ADDRESS_ADD", ownerEntity, request.ownerId(),
                "Added " + type.toLowerCase() + " address", Map.of("addressId", id.toString(),
                        "addressType", type, "isPrimary", request.isPrimary()));
        return addresses(ownerEntity, request.ownerId()).stream()
                .filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Address not found after insert"));
    }

    private void demotePrimaryAddress(String ownerEntity, UUID ownerId, String type) {
        jdbc.update("""
                update crm.postal_address set is_primary = false, updated_at = now()
                where tenant_id = ? and owner_entity = ? and owner_id = ? and address_type = ?
                  and is_primary = true and deleted_at is null
                """, TenantContext.get().tenantId(), ownerEntity, ownerId, type);
    }

    // ----------------------------------------------------------------- channels

    @Transactional(readOnly = true)
    public List<ChannelRow> channels(UUID contactId) {
        return jdbc.query("""
                select id, contact_id, channel, channel_type, value, is_primary, verified_at
                from crm.contact_channel
                where tenant_id = ? and contact_id = ? and deleted_at is null
                order by channel, is_primary desc, channel_type
                """, (rs, i) -> new ChannelRow(rs.getObject("id", UUID.class),
                rs.getObject("contact_id", UUID.class), rs.getString("channel"),
                rs.getString("channel_type"), rs.getString("value"), rs.getBoolean("is_primary"),
                rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant()),
                TenantContext.get().tenantId(), contactId);
    }

    @Transactional
    public List<ChannelRow> addChannel(UUID contactId, ChannelRequest request) {
        actor.bind();
        get(contactId);
        String channel = AccountService.upper(request.channel());
        if (request.isPrimary()) {
            jdbc.update("""
                    update crm.contact_channel set is_primary = false
                    where tenant_id = ? and contact_id = ? and channel = ?
                      and is_primary = true and deleted_at is null
                    """, TenantContext.get().tenantId(), contactId, channel);
        }
        UUID id = jdbc.queryForObject("""
                insert into crm.contact_channel
                  (tenant_id, contact_id, channel, channel_type, value, is_primary)
                values (?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, TenantContext.get().tenantId(), contactId, channel,
                AccountService.upper(request.channelType()),
                AccountService.require(request.value(), "Channel value is required"), request.isPrimary());
        audit.record("CONTACT_CHANNEL_ADD", "CONTACT", contactId,
                "Added " + channel.toLowerCase() + " channel",
                Map.of("channelId", id.toString(), "channel", channel, "isPrimary", request.isPrimary()));
        return channels(contactId);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Exposed for the relationship map, which needs a date without a full detail read. */
    static LocalDate today() {
        return LocalDate.now();
    }
}
