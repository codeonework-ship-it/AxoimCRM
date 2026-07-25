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
