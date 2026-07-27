package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import com.axiom.outbox.OutboxWriter;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FR-ACC-001 (account record), FR-ACC-003 (multi-level hierarchy with a
 * derivable ultimate parent).
 *
 * <p>The hierarchy is maintained by the database, not here. V40 installs the
 * triggers that materialize {@code hierarchy_path}, derive
 * {@code ultimate_parent_id}, cascade a reparent to every descendant, and reject
 * a cycle naming both accounts. This service's job is to translate that
 * rejection into an actionable API error — not to duplicate the check, which
 * would leave two implementations to drift apart.
 */
@Service
public class AccountService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final DuplicateService duplicates;
    private final ActorSession actor;
    private final AuthorizationService authorization;
    private final OutboxWriter outbox;

    public AccountService(JdbcTemplate jdbc, AuditService audit,
                          DuplicateService duplicates, ActorSession actor,
                          AuthorizationService authorization, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.duplicates = duplicates;
        this.actor = actor;
        this.authorization = authorization;
        this.outbox = outbox;
    }

    // --------------------------------------------------------------- contracts

    public record AccountRequest(
            @NotBlank(message = "Account name is required") @Size(max = 240) String name,
            String legalName, String accountNumber, String recordType, UUID parentAccountId,
            UUID ownerId, String businessUnit, String territory, String industry, String segment,
            Integer employeeCount, BigDecimal annualRevenue, String currencyCode, String website,
            String emailDomain, String phone, String status, String sourceSystem, String externalRef,
            boolean acknowledgeDuplicates, String duplicateReason) {}

    public record AccountDetail(UUID id, String name, String legalName, String accountNumber,
                                String recordType, UUID parentAccountId, String parentAccountName,
                                UUID ultimateParentId, String ultimateParentName, int hierarchyDepth,
                                String hierarchyPath, UUID ownerId, String ownerName,
                                String businessUnit, String territory, String industry, String segment,
                                Integer employeeCount, BigDecimal annualRevenue, String currencyCode,
                                String website, String emailDomain, String phone, String status,
                                Integer healthScore, String healthBand, Instant healthComputedAt,
                                String sourceSystem, String externalRef, UUID mergedIntoId,
                                Instant createdAt, Instant updatedAt, long version,
                                List<String> fieldsHiddenByPermission) {}

    public record HierarchyNode(UUID id, String name, UUID parentAccountId, int depth, String status,
                                Integer healthScore, String healthBand, boolean isSelf) {}

    /**
     * @param restricted    the viewer's access narrows the tree; hidden branches are
     *                      absent and are deliberately not counted anywhere
     */
    public record HierarchyView(UUID accountId, UUID ultimateParentId, String ultimateParentName,
                                List<HierarchyNode> nodes, boolean restricted, String restrictionNote) {}

    public record ReparentRequest(UUID parentAccountId, String reason) {}
    public record LifecycleRequest(boolean active, long expectedVersion,
                                   @NotBlank(message = "A lifecycle change reason is required") String reason) {}

    // ------------------------------------------------------------------ reading

    private static final String DETAIL_COLUMNS = """
            a.id, a.name, a.legal_name, a.account_number, a.record_type, a.parent_account_id,
            p.name as parent_name, a.ultimate_parent_id, up.name as ultimate_parent_name,
            a.hierarchy_depth, a.hierarchy_path, a.owner_id, u.display_name as owner_name,
            a.business_unit, a.territory, a.industry, a.segment, a.employee_count,
            a.annual_revenue, a.currency_code, a.website, a.email_domain, a.phone, a.status,
            a.health_score, a.health_band, a.health_computed_at, a.source_system, a.external_ref,
            a.merged_into_id, a.created_at, a.updated_at, a.version
            """;

    private RowMapper<AccountDetail> detailMapper() {
        String role = TenantContext.get().role();
        return (rs, i) -> {
            List<String> hidden = new ArrayList<>();
            BigDecimal revenue = rs.getBigDecimal("annual_revenue");
            if (!FieldVisibility.canRead(role, "annualRevenue")) {
                revenue = null;
                hidden.add("annualRevenue");
            }
            Integer employees = (Integer) rs.getObject("employee_count");
            if (!FieldVisibility.canRead(role, "employeeCount")) {
                employees = null;
                hidden.add("employeeCount");
            }
            return new AccountDetail(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("legal_name"),
                    rs.getString("account_number"), rs.getString("record_type"),
                    rs.getObject("parent_account_id", UUID.class), rs.getString("parent_name"),
                    rs.getObject("ultimate_parent_id", UUID.class), rs.getString("ultimate_parent_name"),
                    rs.getInt("hierarchy_depth"), rs.getString("hierarchy_path"),
                    rs.getObject("owner_id", UUID.class), rs.getString("owner_name"),
                    rs.getString("business_unit"), rs.getString("territory"), rs.getString("industry"),
                    rs.getString("segment"), employees, revenue, rs.getString("currency_code"),
                    rs.getString("website"), rs.getString("email_domain"), rs.getString("phone"),
                    rs.getString("status"), (Integer) rs.getObject("health_score"),
                    rs.getString("health_band"),
                    rs.getTimestamp("health_computed_at") == null ? null : rs.getTimestamp("health_computed_at").toInstant(),
                    rs.getString("source_system"), rs.getString("external_ref"),
                    rs.getObject("merged_into_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                    rs.getLong("version"), List.copyOf(hidden));
        };
    }

    @Transactional(readOnly = true)
    public AccountDetail get(UUID id) {
        authorization.requireRead(SecurableObject.ACCOUNT, id);
        try {
            return jdbc.queryForObject("""
                    select %s
                    from crm.account a
                    left join crm.account p on p.tenant_id = a.tenant_id and p.id = a.parent_account_id
                    left join crm.account up on up.tenant_id = a.tenant_id and up.id = a.ultimate_parent_id
                    left join identity.app_user u on u.tenant_id = a.tenant_id and u.id = a.owner_id
                    where a.tenant_id = ? and a.id = ? and a.deleted_at is null
                    """.formatted(DETAIL_COLUMNS), detailMapper(), TenantContext.get().tenantId(), id);
        } catch (EmptyResultDataAccessException ex) {
            throw new NotFoundException("Account not found, or it has been merged away or deleted");
        }
    }

    /**
     * FR-ACC-003: the whole family, read from the materialized path in one index
     * scan rather than a recursive CTE at read time.
     */
    @Transactional(readOnly = true)
    public HierarchyView hierarchy(UUID id) {
        AccountDetail self = get(id);
        UUID root = self.ultimateParentId() == null ? self.id() : self.ultimateParentId();
        AuthorizationService.RecordPredicate visible = authorization.visibleRecordPredicate(
                SecurableObject.ACCOUNT, "a");
        List<Object> args = new ArrayList<>();
        args.add(TenantContext.get().tenantId());
        args.add(root);
        args.addAll(visible.args());
        List<HierarchyNode> nodes = jdbc.query("""
                select a.id, a.name, a.parent_account_id, a.hierarchy_depth, a.status,
                       a.health_score, a.health_band
                from crm.account a
                where a.tenant_id = ? and a.deleted_at is null and a.ultimate_parent_id = ?
                """ + " and (" + visible.sql() + ")" + """
                order by a.hierarchy_depth, a.name
                """, (rs, i) -> new HierarchyNode(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("parent_account_id", UUID.class), rs.getInt("hierarchy_depth"),
                        rs.getString("status"), (Integer) rs.getObject("health_score"),
                        rs.getString("health_band"), rs.getObject("id", UUID.class).equals(id)),
                args.toArray());
        return new HierarchyView(id, self.ultimateParentId(), self.ultimateParentName(), nodes,
                !visible.allowsEverything(), !visible.allowsEverything() ? restrictionNote() : null);
    }

    // ----------------------------------------------------------------- writing

    @Transactional
    public AccountDetail create(AccountRequest request) {
        authorization.requireCreate(SecurableObject.ACCOUNT);
        actor.bind();
        String name = require(request.name(), "Account name is required");
        DuplicateService.Assessment assessment = duplicates.assess(new DuplicateService.Probe(
                "ACCOUNT", name, name, null, request.phone(),
                request.website() != null ? request.website() : request.emailDomain(), null));
        guardDuplicates(assessment, "ACCOUNT", null, "CREATE",
                request.acknowledgeDuplicates(), request.duplicateReason(), name);

        UUID id = jdbc.queryForObject("""
                insert into crm.account
                  (tenant_id, name, legal_name, account_number, record_type, parent_account_id,
                   owner_id, business_unit, territory, industry, segment, employee_count,
                   annual_revenue, currency_code, website, email_domain, phone, status,
                   source_system, external_ref, created_by, updated_by)
                values (?, ?, ?, ?, coalesce(?, 'STANDARD'), ?, ?, ?, ?, ?, ?, ?, ?,
                        coalesce(?, 'INR'), ?, ?, ?, coalesce(?, 'ACTIVE'), ?, ?, ?, ?)
                returning id
                """, UUID.class,
                TenantContext.get().tenantId(), name, blankToNull(request.legalName()),
                blankToNull(request.accountNumber()), upper(request.recordType()), request.parentAccountId(),
                request.ownerId() == null ? TenantContext.get().userId() : request.ownerId(),
                blankToNull(request.businessUnit()), blankToNull(request.territory()),
                blankToNull(request.industry()), upper(request.segment()), request.employeeCount(),
                request.annualRevenue(), upper(request.currencyCode()), blankToNull(request.website()),
                DuplicateMatcher.normalizeDomain(request.emailDomain() != null ? request.emailDomain() : request.website()),
                blankToNull(request.phone()), upper(request.status()),
                blankToNull(request.sourceSystem()), blankToNull(request.externalRef()),
                TenantContext.get().userId(), TenantContext.get().userId());

        if (assessment.warned()) {
            duplicates.recordDecision("ACCOUNT", id, "CREATE", "PROCEEDED", assessment, request.duplicateReason());
        }
        stampProvenance(id, request);
        audit.record("ACCOUNT_CREATE", "ACCOUNT", id, "Created account " + name,
                Map.of("name", name, "recordType", nullSafe(upper(request.recordType()), "STANDARD"),
                        "parentAccountId", String.valueOf(request.parentAccountId()),
                        "duplicateTopConfidence", assessment.topConfidence()));
        outbox.write("account", id, "account.created", Map.of("accountId", id.toString(), "name", name));
        return get(id);
    }

    @Transactional
    public AccountDetail update(UUID id, long expectedVersion, AccountRequest request) {
        authorization.requireEdit(SecurableObject.ACCOUNT, id);
        actor.bind();
        AccountDetail before = get(id);
        String name = require(request.name(), "Account name is required");

        // FR-ACC-008 applies on update, not only on create.
        DuplicateService.Assessment assessment = duplicates.assess(new DuplicateService.Probe(
                "ACCOUNT", name, name, null, request.phone(),
                request.website() != null ? request.website() : request.emailDomain(), id));
        guardDuplicates(assessment, "ACCOUNT", id, "UPDATE",
                request.acknowledgeDuplicates(), request.duplicateReason(), name);

        int updated = jdbc.update("""
                update crm.account
                set name = ?, legal_name = ?, account_number = ?, record_type = coalesce(?, record_type),
                    owner_id = coalesce(?, owner_id), business_unit = ?, territory = ?, industry = ?,
                    segment = ?, employee_count = ?, annual_revenue = ?,
                    currency_code = coalesce(?, currency_code), website = ?, email_domain = ?,
                    phone = ?, status = coalesce(?, status), source_system = ?, external_ref = ?,
                    updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null and version = ?
                """, name, blankToNull(request.legalName()), blankToNull(request.accountNumber()),
                upper(request.recordType()), request.ownerId(), blankToNull(request.businessUnit()),
                blankToNull(request.territory()), blankToNull(request.industry()), upper(request.segment()),
                request.employeeCount(), request.annualRevenue(), upper(request.currencyCode()),
                blankToNull(request.website()),
                DuplicateMatcher.normalizeDomain(request.emailDomain() != null ? request.emailDomain() : request.website()),
                blankToNull(request.phone()), upper(request.status()), blankToNull(request.sourceSystem()),
                blankToNull(request.externalRef()), TenantContext.get().userId(),
                TenantContext.get().tenantId(), id, expectedVersion);
        if (updated == 0) {
            throw new ConflictException("This account changed while you were editing it (you had version "
                    + expectedVersion + ", the stored record is version " + before.version()
                    + "). Reload the account and re-apply your changes.");
        }
        if (assessment.warned()) {
            duplicates.recordDecision("ACCOUNT", id, "UPDATE", "PROCEEDED", assessment, request.duplicateReason());
        }
        stampProvenance(id, request);
        audit.record("ACCOUNT_UPDATE", "ACCOUNT", id, "Updated account " + name,
                Map.of("previousName", nullSafe(before.name(), ""), "name", name,
                        "fromVersion", expectedVersion));
        outbox.write("account", id, "account.updated", Map.of(
                "accountId", id.toString(), "fromVersion", expectedVersion, "name", name));
        return get(id);
    }

    /**
     * FR-ACC-003 — reparent. The cycle rejection comes back from the database
     * check with both account names already in the message; it is passed through
     * verbatim rather than replaced by a generic "invalid parent".
     */
    @Transactional
    public AccountDetail reparent(UUID id, ReparentRequest request) {
        authorization.requireEdit(SecurableObject.ACCOUNT, id);
        actor.bind();
        AccountDetail before = get(id);
        if (request.parentAccountId() != null) {
            AccountDetail parent = get(request.parentAccountId());
            if (parent.id().equals(id)) {
                throw new ConflictException("An account cannot be its own parent: \"" + before.name()
                        + "\" (" + id + "). Choose a different parent account.");
            }
        }
        try {
            int updated = jdbc.update("""
                    update crm.account
                    set parent_account_id = ?, updated_at = now(), updated_by = ?, version = version + 1
                    where tenant_id = ? and id = ? and deleted_at is null
                    """, request.parentAccountId(), TenantContext.get().userId(),
                    TenantContext.get().tenantId(), id);
            if (updated == 0) throw new NotFoundException("Account not found");
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(databaseMessage(ex,
                    "The parent account could not be assigned because it would create a loop in the hierarchy."));
        }
        AccountDetail after = get(id);
        audit.record("ACCOUNT_REPARENT", "ACCOUNT", id,
                "Moved account " + after.name() + " under "
                        + (after.parentAccountName() == null ? "no parent (now a family head)" : after.parentAccountName()),
                Map.of("previousParentAccountId", String.valueOf(before.parentAccountId()),
                        "parentAccountId", String.valueOf(after.parentAccountId()),
                        "hierarchyPath", after.hierarchyPath(),
                        "ultimateParentId", String.valueOf(after.ultimateParentId()),
                        "reason", nullSafe(request.reason(), "")));
        outbox.write("account", id, "account.reparented", Map.of(
                "accountId", id.toString(), "parentAccountId", String.valueOf(after.parentAccountId()),
                "reason", nullSafe(request.reason(), "")));
        return after;
    }

    /** Soft lifecycle only: account rows are never physically deleted by the API. */
    @Transactional
    public AccountDetail changeLifecycle(UUID id, LifecycleRequest request) {
        authorization.requireDelete(SecurableObject.ACCOUNT, id);
        actor.bind();
        AccountDetail before = get(id);
        String target = request.active() ? "ACTIVE" : "INACTIVE";
        int updated = jdbc.update("""
                update crm.account set status = ?, updated_at = now(), updated_by = ?, version = version + 1
                where tenant_id = ? and id = ? and deleted_at is null and version = ?
                """, target, TenantContext.get().userId(), TenantContext.get().tenantId(), id,
                request.expectedVersion());
        if (updated == 0) {
            throw new ConflictException("This account changed while you were editing it. Reload and try again.");
        }
        audit.recordWithReason("ACCOUNT_" + target, "ACCOUNT", id,
                (request.active() ? "Reactivated " : "Inactivated ") + before.name(), request.reason(),
                Map.of("before", Map.of("status", before.status()), "after", Map.of("status", target),
                        "fromVersion", request.expectedVersion()));
        outbox.write("account", id, request.active() ? "account.reactivated" : "account.inactivated",
                Map.of("accountId", id.toString(), "reason", request.reason()));
        return get(id);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Blocking rules refuse; warning rules are permitted once acknowledged. Both
     * paths record what the user was shown, so a later question about why a
     * near-duplicate exists has an answer.
     */
    private void guardDuplicates(DuplicateService.Assessment assessment, String entityType, UUID entityId,
                                 String operation, boolean acknowledged, String reason, String label) {
        if (assessment.blocked()) {
            duplicates.recordDecision(entityType, entityId, operation, "BLOCKED", assessment, reason);
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

    /** FR-ACC-013: a value a person typed is marked as such, so enrichment cannot quietly replace it. */
    private void stampProvenance(UUID accountId, AccountRequest request) {
        Map<String, Object> userEntered = new LinkedHashMap<>();
        if (request.website() != null && !request.website().isBlank()) userEntered.put("website", request.website());
        if (request.phone() != null && !request.phone().isBlank()) userEntered.put("phone", request.phone());
        if (request.industry() != null && !request.industry().isBlank()) userEntered.put("industry", request.industry());
        if (request.employeeCount() != null) userEntered.put("employeeCount", request.employeeCount());
        if (request.annualRevenue() != null) userEntered.put("annualRevenue", request.annualRevenue());
        if (request.legalName() != null && !request.legalName().isBlank()) userEntered.put("legalName", request.legalName());
        userEntered.keySet().forEach(field -> jdbc.update("""
                insert into crm.field_provenance
                  (tenant_id, entity_type, entity_id, field_name, value_source, recorded_by)
                values (?, 'ACCOUNT', ?, ?, 'USER', ?)
                on conflict (tenant_id, entity_type, entity_id, field_name)
                do update set value_source = 'USER', provider_code = null, confidence = null,
                              recorded_at = now(), recorded_by = excluded.recorded_by
                """, TenantContext.get().tenantId(), accountId, field, TenantContext.get().userId()));
    }

    static String databaseMessage(DataIntegrityViolationException ex, String fallback) {
        Throwable root = ex.getMostSpecificCause();
        String message = root == null ? null : root.getMessage();
        if (message == null || message.isBlank()) return fallback;
        int detail = message.indexOf('\n');
        return detail > 0 ? message.substring(0, detail).trim() : message.trim();
    }

    static String require(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String upper(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    static String nullSafe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String restrictionNote() {
        return "Totals cover only the records your access permits. Records outside your access are excluded.";
    }
}
