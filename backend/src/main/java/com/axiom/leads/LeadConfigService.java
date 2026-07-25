package com.axiom.leads;

import com.axiom.audit.AuditService;
import com.axiom.auth.CrmRole;
import com.axiom.common.ConflictException;
import com.axiom.common.NotFoundException;
import com.axiom.tenancy.TenantContext;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything an administrator configures about the lead lifecycle: the status
 * model (FR-LED-001), the duplicate policy (FR-LED-004), the scoring rules
 * (FR-LED-006), the predictive factors (FR-LED-007), the routing rules
 * (FR-LED-008), business hours and the response SLA (FR-LED-009), the
 * qualification framework (FR-LED-010) and the conversion field map
 * (FR-LED-011).
 *
 * <p>{@link #ensureTenantDefaults()} exists because V50 seeds these for the
 * tenants that existed when it ran, and a tenant provisioned afterwards would
 * otherwise have an empty status model — which would make lead creation fail
 * with a configuration error the customer did not cause. The two must be kept in
 * step; the migration says so at the seeding block.
 */
@Service
public class LeadConfigService {

    /** The status model a tenant starts with: code, label, category, order, default. */
    static final List<Object[]> DEFAULT_STATUSES = List.of(
            new Object[]{"NEW", "New", "OPEN", 10, true},
            new Object[]{"WORKING", "Working", "OPEN", 20, false},
            new Object[]{"NURTURING", "Nurturing", "OPEN", 30, false},
            new Object[]{"REVIEW", "Duplicate review", "OPEN", 40, false},
            new Object[]{"QUALIFIED", "Qualified", "OPEN", 50, false},
            new Object[]{"CONVERTED", "Converted", "CONVERTED", 60, false},
            new Object[]{"DISQUALIFIED", "Disqualified", "DISQUALIFIED", 70, false},
            new Object[]{"RECYCLED", "Recycled to nurture", "RECYCLED", 80, false});

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public LeadConfigService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- records

    public record StatusRow(UUID id, String code, String label, String category, int sortOrder,
                            boolean active, boolean isDefault) {
        public boolean terminal() { return !"OPEN".equals(category); }
    }

    public record QueueRow(UUID id, String code, String name, boolean fallback,
                           UUID escalationUserId, String escalationUserName, boolean active) {}

    public record ScoringRuleRow(UUID id, String name, String category, String fieldKey, String operator,
                                 String comparisonValue, int points, int sortOrder, boolean active) {}

    public record PredictiveFactorRow(UUID id, String factorKey, String label, String fieldKey, String operator,
                                      String comparisonValue, BigDecimal weight, int sortOrder, boolean active) {}

    public record AssignmentMemberRow(UUID id, UUID userId, String userName, int sortOrder,
                                      Integer capacity, boolean active, long openLeads) {}

    public record AssignmentRuleRow(UUID id, String name, int sortOrder, boolean active,
                                    String matchTerritory, String matchSegment, String matchProductInterest,
                                    String matchSource, Integer matchMinScore, String assignmentMode,
                                    UUID targetUserId, String targetUserName, UUID targetQueueId,
                                    String targetQueueName, UUID slaPolicyId,
                                    List<AssignmentMemberRow> members) {}

    public record BusinessHoursDayRow(int dayOfWeek, LocalTime openTime, LocalTime closeTime) {}

    public record HolidayRow(LocalDate date, String name) {}

    public record BusinessHoursRow(UUID id, String code, String name, String timeZone, boolean isDefault,
                                   List<BusinessHoursDayRow> days, List<HolidayRow> holidays) {}

    public record SlaPolicyRow(UUID id, String code, String name, int firstResponseMinutes,
                               UUID businessHoursId, String businessHoursName,
                               UUID escalationUserId, String escalationUserName,
                               boolean isDefault, boolean active) {}

    public record QualificationFieldRow(UUID id, String fieldKey, String label, String fieldType,
                                        boolean required, int sortOrder, String opportunityField) {}

    public record QualificationFrameworkRow(UUID id, String code, String name, boolean isDefault,
                                            boolean active, List<QualificationFieldRow> fields) {}

    public record DuplicatePolicyRow(String behaviour, boolean matchEmail, boolean matchPhone,
                                     boolean matchCompanyDomain, BigDecimal nameSimilarityThreshold,
                                     BigDecimal reviewConfidenceFloor) {}

    public record ConversionMappingRow(UUID id, String targetEntity, String sourceExpression,
                                       String targetField, boolean customField, int sortOrder, boolean active) {}

    public record CaptureFormRow(UUID id, String formKey, String name, boolean active, String botProtection,
                                 String honeypotField, int minFillSeconds, List<String> requiredFields,
                                 Map<String, String> fieldMap, String defaultSource, String defaultStatus,
                                 String defaultCampaignCode, UUID defaultQueueId, String embedUrl) {}

    public record ReasonRow(String code, String label) {}

    /** Everything the configuration screen needs in one round trip. */
    public record ConfigBundle(List<StatusRow> statuses, List<QueueRow> queues,
                               List<ScoringRuleRow> scoringRules, List<PredictiveFactorRow> predictiveFactors,
                               List<AssignmentRuleRow> assignmentRules, List<BusinessHoursRow> businessHours,
                               List<SlaPolicyRow> slaPolicies, List<QualificationFrameworkRow> frameworks,
                               DuplicatePolicyRow duplicatePolicy, List<ConversionMappingRow> conversionMappings,
                               List<CaptureFormRow> captureForms, List<ReasonRow> disqualificationReasons,
                               Map<String, String> ruleFieldKeys, List<String> ruleOperators) {}

    // ------------------------------------------------------------- provisioning

    /**
     * Idempotently seeds the lead configuration for a tenant that has none.
     * Cheap: one COUNT on the common path.
     */
    @Transactional
    public void ensureTenantDefaults() {
        UUID tenantId = TenantContext.get().tenantId();
        Integer statuses = jdbc.queryForObject(
                "select count(*) from leads.lead_status where tenant_id = ?", Integer.class, tenantId);
        if (statuses != null && statuses > 0) {
            return;
        }
        for (Object[] status : DEFAULT_STATUSES) {
            jdbc.update("""
                    insert into leads.lead_status (tenant_id, code, label, category, sort_order, is_default)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, code) do nothing
                    """, tenantId, status[0], status[1], status[2], status[3], status[4]);
        }
        jdbc.update("""
                insert into leads.lead_queue (tenant_id, code, name, is_fallback, escalation_user_id)
                select ?, 'UNROUTED', 'Unrouted lead queue', true,
                       (select id from identity.app_user where tenant_id = ? and role = 'TENANT_ADMIN'
                        order by email limit 1)
                on conflict (tenant_id, code) do nothing
                """, tenantId, tenantId);
        jdbc.update("""
                insert into leads.business_hours (tenant_id, code, name, time_zone, is_default)
                values (?, 'STANDARD', 'Standard selling hours', 'UTC', true)
                on conflict (tenant_id, code) do nothing
                """, tenantId);
        jdbc.update("""
                insert into leads.business_hours_day (tenant_id, business_hours_id, day_of_week, open_time, close_time)
                select bh.tenant_id, bh.id, d.dow, time '09:00', time '18:00'
                from leads.business_hours bh cross join (values (1),(2),(3),(4),(5)) as d(dow)
                where bh.tenant_id = ? and bh.code = 'STANDARD'
                on conflict (tenant_id, business_hours_id, day_of_week) do nothing
                """, tenantId);
        jdbc.update("""
                insert into leads.sla_policy (tenant_id, code, name, first_response_minutes,
                                              business_hours_id, escalation_user_id, is_default)
                select ?, 'FIRST_RESPONSE', 'Speed to lead — first response', 120,
                       (select id from leads.business_hours where tenant_id = ? and code = 'STANDARD'),
                       (select id from identity.app_user where tenant_id = ? and role = 'TENANT_ADMIN'
                        order by email limit 1),
                       true
                on conflict (tenant_id, code) do nothing
                """, tenantId, tenantId, tenantId);
        jdbc.update("insert into leads.duplicate_policy (tenant_id) values (?) on conflict do nothing", tenantId);
        jdbc.update("""
                insert into leads.predictive_model (tenant_id, provider, model_version, intercept)
                values (?, 'LOCAL_LOGISTIC', 'v1', -1.4) on conflict do nothing
                """, tenantId);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<StatusRow> statuses() {
        return jdbc.query("""
                select id, code, label, category, sort_order, active, is_default
                from leads.lead_status where tenant_id = ? order by sort_order
                """, (rs, i) -> new StatusRow(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("label"), rs.getString("category"), rs.getInt("sort_order"),
                rs.getBoolean("active"), rs.getBoolean("is_default")), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<QueueRow> queues() {
        return jdbc.query("""
                select q.id, q.code, q.name, q.is_fallback, q.escalation_user_id, q.active,
                       u.display_name as escalation_user_name
                from leads.lead_queue q
                left join identity.app_user u on u.tenant_id = q.tenant_id and u.id = q.escalation_user_id
                where q.tenant_id = ? order by q.is_fallback desc, q.name
                """, (rs, i) -> new QueueRow(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getBoolean("is_fallback"),
                rs.getObject("escalation_user_id", UUID.class), rs.getString("escalation_user_name"),
                rs.getBoolean("active")), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<ScoringRuleRow> scoringRules() {
        return jdbc.query("""
                select id, name, category, field_key, operator, comparison_value, points, sort_order, active
                from leads.scoring_rule where tenant_id = ? order by sort_order, name
                """, this::mapScoringRule, TenantContext.get().tenantId());
    }

    private ScoringRuleRow mapScoringRule(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ScoringRuleRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                rs.getString("field_key"), rs.getString("operator"), rs.getString("comparison_value"),
                rs.getInt("points"), rs.getInt("sort_order"), rs.getBoolean("active"));
    }

    @Transactional(readOnly = true)
    public List<PredictiveFactorRow> predictiveFactors() {
        return jdbc.query("""
                select id, factor_key, label, field_key, operator, comparison_value, weight, sort_order, active
                from leads.predictive_factor where tenant_id = ? order by sort_order
                """, (rs, i) -> new PredictiveFactorRow(rs.getObject("id", UUID.class), rs.getString("factor_key"),
                rs.getString("label"), rs.getString("field_key"), rs.getString("operator"),
                rs.getString("comparison_value"), rs.getBigDecimal("weight"), rs.getInt("sort_order"),
                rs.getBoolean("active")), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<AssignmentRuleRow> assignmentRules() {
        UUID tenantId = TenantContext.get().tenantId();
        List<AssignmentMemberRow> allMembers = new ArrayList<>();
        Map<UUID, List<AssignmentMemberRow>> byRule = new java.util.HashMap<>();
        jdbc.query("""
                select m.id, m.rule_id, m.user_id, m.sort_order, m.capacity, m.active,
                       u.display_name as user_name,
                       (select count(*) from crm.lead l
                        where l.tenant_id = m.tenant_id and l.owner_id = m.user_id
                          and l.deleted_at is null and l.converted_at is null and l.disqualified_at is null
                       ) as open_leads
                from leads.assignment_rule_member m
                join identity.app_user u on u.tenant_id = m.tenant_id and u.id = m.user_id
                where m.tenant_id = ? order by m.sort_order
                """, rs -> {
            UUID ruleId = rs.getObject("rule_id", UUID.class);
            AssignmentMemberRow row = new AssignmentMemberRow(rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class), rs.getString("user_name"), rs.getInt("sort_order"),
                    (Integer) rs.getObject("capacity"), rs.getBoolean("active"), rs.getLong("open_leads"));
            allMembers.add(row);
            byRule.computeIfAbsent(ruleId, k -> new ArrayList<>()).add(row);
        }, tenantId);

        return jdbc.query("""
                select r.id, r.name, r.sort_order, r.active, r.match_territory, r.match_segment,
                       r.match_product_interest, r.match_source, r.match_min_score, r.assignment_mode,
                       r.target_user_id, r.target_queue_id, r.sla_policy_id,
                       u.display_name as target_user_name, q.name as target_queue_name
                from leads.assignment_rule r
                left join identity.app_user u on u.tenant_id = r.tenant_id and u.id = r.target_user_id
                left join leads.lead_queue q on q.tenant_id = r.tenant_id and q.id = r.target_queue_id
                where r.tenant_id = ? order by r.sort_order
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new AssignmentRuleRow(id, rs.getString("name"), rs.getInt("sort_order"), rs.getBoolean("active"),
                    rs.getString("match_territory"), rs.getString("match_segment"),
                    rs.getString("match_product_interest"), rs.getString("match_source"),
                    (Integer) rs.getObject("match_min_score"), rs.getString("assignment_mode"),
                    rs.getObject("target_user_id", UUID.class), rs.getString("target_user_name"),
                    rs.getObject("target_queue_id", UUID.class), rs.getString("target_queue_name"),
                    rs.getObject("sla_policy_id", UUID.class),
                    byRule.getOrDefault(id, List.of()));
        }, tenantId);
    }

    @Transactional(readOnly = true)
    public List<BusinessHoursRow> businessHours() {
        UUID tenantId = TenantContext.get().tenantId();
        Map<UUID, List<BusinessHoursDayRow>> days = new java.util.HashMap<>();
        jdbc.query("""
                select business_hours_id, day_of_week, open_time, close_time
                from leads.business_hours_day where tenant_id = ? order by day_of_week
                """, (RowCallbackHandler) rs -> days.computeIfAbsent(rs.getObject("business_hours_id", UUID.class), k -> new ArrayList<>())
                .add(new BusinessHoursDayRow(rs.getInt("day_of_week"),
                        rs.getObject("open_time", LocalTime.class), rs.getObject("close_time", LocalTime.class))),
                tenantId);
        Map<UUID, List<HolidayRow>> holidays = new java.util.HashMap<>();
        jdbc.query("""
                select business_hours_id, holiday_date, name
                from leads.business_hours_holiday where tenant_id = ? order by holiday_date
                """, (RowCallbackHandler) rs -> holidays.computeIfAbsent(rs.getObject("business_hours_id", UUID.class), k -> new ArrayList<>())
                .add(new HolidayRow(rs.getObject("holiday_date", LocalDate.class), rs.getString("name"))),
                tenantId);
        return jdbc.query("""
                select id, code, name, time_zone, is_default
                from leads.business_hours where tenant_id = ? order by is_default desc, name
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new BusinessHoursRow(id, rs.getString("code"), rs.getString("name"), rs.getString("time_zone"),
                    rs.getBoolean("is_default"), days.getOrDefault(id, List.of()), holidays.getOrDefault(id, List.of()));
        }, tenantId);
    }

    @Transactional(readOnly = true)
    public List<SlaPolicyRow> slaPolicies() {
        return jdbc.query("""
                select p.id, p.code, p.name, p.first_response_minutes, p.business_hours_id,
                       p.escalation_user_id, p.is_default, p.active,
                       bh.name as business_hours_name, u.display_name as escalation_user_name
                from leads.sla_policy p
                left join leads.business_hours bh on bh.tenant_id = p.tenant_id and bh.id = p.business_hours_id
                left join identity.app_user u on u.tenant_id = p.tenant_id and u.id = p.escalation_user_id
                where p.tenant_id = ? order by p.is_default desc, p.name
                """, (rs, i) -> new SlaPolicyRow(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getInt("first_response_minutes"),
                rs.getObject("business_hours_id", UUID.class), rs.getString("business_hours_name"),
                rs.getObject("escalation_user_id", UUID.class), rs.getString("escalation_user_name"),
                rs.getBoolean("is_default"), rs.getBoolean("active")), TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    public List<QualificationFrameworkRow> frameworks() {
        UUID tenantId = TenantContext.get().tenantId();
        Map<UUID, List<QualificationFieldRow>> fields = new java.util.HashMap<>();
        jdbc.query("""
                select id, framework_id, field_key, label, field_type, required, sort_order, opportunity_field
                from leads.qualification_field where tenant_id = ? order by sort_order
                """, (RowCallbackHandler) rs -> fields.computeIfAbsent(rs.getObject("framework_id", UUID.class), k -> new ArrayList<>())
                .add(new QualificationFieldRow(rs.getObject("id", UUID.class), rs.getString("field_key"),
                        rs.getString("label"), rs.getString("field_type"), rs.getBoolean("required"),
                        rs.getInt("sort_order"), rs.getString("opportunity_field"))), tenantId);
        return jdbc.query("""
                select id, code, name, is_default, active from leads.qualification_framework
                where tenant_id = ? order by is_default desc, name
                """, (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new QualificationFrameworkRow(id, rs.getString("code"), rs.getString("name"),
                    rs.getBoolean("is_default"), rs.getBoolean("active"), fields.getOrDefault(id, List.of()));
        }, tenantId);
    }

    @Transactional(readOnly = true)
    public DuplicatePolicyRow duplicatePolicy() {
        List<DuplicatePolicyRow> rows = jdbc.query("""
                select behaviour, match_email, match_phone, match_company_domain,
                       name_similarity_threshold, review_confidence_floor
                from leads.duplicate_policy where tenant_id = ?
                """, (rs, i) -> new DuplicatePolicyRow(rs.getString("behaviour"), rs.getBoolean("match_email"),
                rs.getBoolean("match_phone"), rs.getBoolean("match_company_domain"),
                rs.getBigDecimal("name_similarity_threshold"), rs.getBigDecimal("review_confidence_floor")),
                TenantContext.get().tenantId());
        return rows.isEmpty()
                ? new DuplicatePolicyRow("ATTACH", true, true, true,
                        new BigDecimal("0.700"), new BigDecimal("0.500"))
                : rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<ConversionMappingRow> conversionMappings() {
        return jdbc.query("""
                select id, target_entity, source_expression, target_field, custom_field, sort_order, active
                from leads.conversion_mapping where tenant_id = ? order by target_entity, sort_order
                """, (rs, i) -> new ConversionMappingRow(rs.getObject("id", UUID.class),
                rs.getString("target_entity"), rs.getString("source_expression"), rs.getString("target_field"),
                rs.getBoolean("custom_field"), rs.getInt("sort_order"), rs.getBoolean("active")),
                TenantContext.get().tenantId());
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<CaptureFormRow> captureForms() {
        return jdbc.query("""
                select id, form_key, name, active, bot_protection, honeypot_field, min_fill_seconds,
                       required_fields, field_map::text as field_map, default_source, default_status,
                       default_campaign_code, default_queue_id
                from leads.capture_form where tenant_id = ? order by name
                """, (rs, i) -> {
            String[] required = (String[]) rs.getArray("required_fields").getArray();
            Map<String, String> map;
            try {
                map = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(rs.getString("field_map"), Map.class);
            } catch (Exception ex) {
                map = Map.of();
            }
            String formKey = rs.getString("form_key");
            return new CaptureFormRow(rs.getObject("id", UUID.class), formKey, rs.getString("name"),
                    rs.getBoolean("active"), rs.getString("bot_protection"), rs.getString("honeypot_field"),
                    rs.getInt("min_fill_seconds"), List.of(required), map, rs.getString("default_source"),
                    rs.getString("default_status"), rs.getString("default_campaign_code"),
                    rs.getObject("default_queue_id", UUID.class),
                    "/public/lead-capture/" + formKey);
        }, TenantContext.get().tenantId());
    }

    /**
     * The governed disqualification taxonomy (FR-LED-012). Read from the
     * reference module rather than owned here: a data steward already governs
     * value sets with effective dating and audit, and a second private taxonomy
     * would be a second place to keep correct.
     */
    @Transactional(readOnly = true)
    public List<ReasonRow> disqualificationReasons() {
        return jdbc.query("""
                select e.code, e.label
                from reference.value_set_entry e
                join reference.value_set v on v.tenant_id = e.tenant_id and v.id = e.value_set_id
                where e.tenant_id = ? and v.api_name = 'lead_disqualification_reason'
                  and e.active = true and v.active = true
                  and (e.effective_from is null or e.effective_from <= current_date)
                  and (e.effective_to is null or e.effective_to >= current_date)
                order by e.sort_order
                """, (rs, i) -> new ReasonRow(rs.getString("code"), rs.getString("label")),
                TenantContext.get().tenantId());
    }

    @Transactional
    public ConfigBundle bundle() {
        ensureTenantDefaults();
        return new ConfigBundle(statuses(), queues(), scoringRules(), predictiveFactors(), assignmentRules(),
                businessHours(), slaPolicies(), frameworks(), duplicatePolicy(), conversionMappings(),
                captureForms(), disqualificationReasons(), LeadSnapshot.keys(), RuleOperators.SUPPORTED);
    }

    // ----------------------------------------------------------------- writes

    public record StatusRequest(@NotBlank String code, @NotBlank String label, @NotBlank String category,
                                @NotNull Integer sortOrder, Boolean active) {}

    @Transactional
    public StatusRow saveStatus(StatusRequest request) {
        requireAdmin();
        UUID tenantId = TenantContext.get().tenantId();
        String code = normalizeCode(request.code());
        String category = request.category().trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "CONVERTED", "DISQUALIFIED", "RECYCLED").contains(category)) {
            throw new IllegalArgumentException(
                    "Status category must be OPEN, CONVERTED, DISQUALIFIED or RECYCLED — the three "
                            + "non-open categories are the terminal states reporting relies on.");
        }
        try {
            jdbc.update("""
                    insert into leads.lead_status (tenant_id, code, label, category, sort_order, active)
                    values (?, ?, ?, ?, ?, coalesce(?, true))
                    on conflict (tenant_id, code) do update
                      set label = excluded.label, category = excluded.category,
                          sort_order = excluded.sort_order, active = excluded.active, updated_at = now()
                    """, tenantId, code, request.label().trim(), category, request.sortOrder(), request.active());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Another status already uses that order position or terminal category. "
                    + "Give this one a different position, or change the other status first.");
        }
        audit.record("LEAD_STATUS_SAVE", "LEAD_STATUS", null, "Saved lead status " + code,
                Map.of("code", code, "category", category, "sortOrder", request.sortOrder()));
        return statuses().stream().filter(s -> s.code().equals(code)).findFirst()
                .orElseThrow(() -> new NotFoundException("Lead status not found after save"));
    }

    public record DuplicatePolicyRequest(@NotBlank String behaviour, Boolean matchEmail, Boolean matchPhone,
                                         Boolean matchCompanyDomain, BigDecimal nameSimilarityThreshold,
                                         BigDecimal reviewConfidenceFloor) {}

    @Transactional
    public DuplicatePolicyRow saveDuplicatePolicy(DuplicatePolicyRequest request) {
        requireAdmin();
        String behaviour = request.behaviour().trim().toUpperCase(Locale.ROOT);
        if (!List.of("CREATE", "MERGE", "ATTACH", "REVIEW").contains(behaviour)) {
            throw new IllegalArgumentException(
                    "Duplicate behaviour must be CREATE, MERGE, ATTACH or REVIEW.");
        }
        DuplicatePolicyRow current = duplicatePolicy();
        jdbc.update("""
                insert into leads.duplicate_policy (tenant_id, behaviour, match_email, match_phone,
                                                    match_company_domain, name_similarity_threshold,
                                                    review_confidence_floor, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, now())
                on conflict (tenant_id) do update
                  set behaviour = excluded.behaviour, match_email = excluded.match_email,
                      match_phone = excluded.match_phone,
                      match_company_domain = excluded.match_company_domain,
                      name_similarity_threshold = excluded.name_similarity_threshold,
                      review_confidence_floor = excluded.review_confidence_floor, updated_at = now()
                """, TenantContext.get().tenantId(), behaviour,
                request.matchEmail() == null ? current.matchEmail() : request.matchEmail(),
                request.matchPhone() == null ? current.matchPhone() : request.matchPhone(),
                request.matchCompanyDomain() == null ? current.matchCompanyDomain() : request.matchCompanyDomain(),
                request.nameSimilarityThreshold() == null ? current.nameSimilarityThreshold() : request.nameSimilarityThreshold(),
                request.reviewConfidenceFloor() == null ? current.reviewConfidenceFloor() : request.reviewConfidenceFloor());
        audit.record("LEAD_DUPLICATE_POLICY_SAVE", "LEAD_CONFIG", null,
                "Duplicate handling set to " + behaviour,
                Map.of("before", current.behaviour(), "after", behaviour));
        return duplicatePolicy();
    }

    public record ScoringRuleRequest(UUID id, @NotBlank String name, @NotBlank String category,
                                     @NotBlank String fieldKey, @NotBlank String operator,
                                     String comparisonValue, @NotNull Integer points,
                                     @NotNull Integer sortOrder, Boolean active) {}

    @Transactional
    public ScoringRuleRow saveScoringRule(ScoringRuleRequest request) {
        requireAdmin();
        validateOperator(request.operator());
        UUID tenantId = TenantContext.get().tenantId();
        String category = request.category().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ATTRIBUTE", "BEHAVIOUR").contains(category)) {
            throw new IllegalArgumentException("Scoring rule category must be ATTRIBUTE or BEHAVIOUR.");
        }
        UUID id = request.id();
        try {
            if (id == null) {
                id = jdbc.queryForObject("""
                        insert into leads.scoring_rule (tenant_id, name, category, field_key, operator,
                                                        comparison_value, points, sort_order, active)
                        values (?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, true)) returning id
                        """, UUID.class, tenantId, request.name().trim(), category, request.fieldKey().trim(),
                        request.operator().trim().toUpperCase(Locale.ROOT), request.comparisonValue(),
                        request.points(), request.sortOrder(), request.active());
            } else {
                int updated = jdbc.update("""
                        update leads.scoring_rule
                        set name = ?, category = ?, field_key = ?, operator = ?, comparison_value = ?,
                            points = ?, sort_order = ?, active = coalesce(?, active), updated_at = now()
                        where tenant_id = ? and id = ?
                        """, request.name().trim(), category, request.fieldKey().trim(),
                        request.operator().trim().toUpperCase(Locale.ROOT), request.comparisonValue(),
                        request.points(), request.sortOrder(), request.active(), tenantId, id);
                if (updated == 0) throw new NotFoundException("Scoring rule not found");
            }
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A scoring rule with that name already exists. Rename one of them.");
        }
        audit.record("LEAD_SCORING_RULE_SAVE", "LEAD_CONFIG", id, "Saved scoring rule " + request.name(),
                Map.of("field", request.fieldKey(), "operator", request.operator(), "points", request.points()));
        UUID finalId = id;
        return scoringRules().stream().filter(r -> r.id().equals(finalId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Scoring rule not found after save"));
    }

    @Transactional
    public void deleteScoringRule(UUID id) {
        requireAdmin();
        int deleted = jdbc.update("delete from leads.scoring_rule where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), id);
        if (deleted == 0) throw new NotFoundException("Scoring rule not found");
        audit.record("LEAD_SCORING_RULE_DELETE", "LEAD_CONFIG", id, "Deleted a scoring rule", Map.of("id", id));
    }

    public record AssignmentMemberRequest(@NotNull UUID userId, @NotNull Integer sortOrder, Integer capacity) {}

    public record AssignmentRuleRequest(UUID id, @NotBlank String name, @NotNull Integer sortOrder, Boolean active,
                                        String matchTerritory, String matchSegment, String matchProductInterest,
                                        String matchSource, Integer matchMinScore,
                                        @NotBlank String assignmentMode, UUID targetUserId, UUID targetQueueId,
                                        UUID slaPolicyId, List<AssignmentMemberRequest> members) {}

    @Transactional
    public AssignmentRuleRow saveAssignmentRule(AssignmentRuleRequest request) {
        requireAdmin();
        UUID tenantId = TenantContext.get().tenantId();
        String mode = request.assignmentMode().trim().toUpperCase(Locale.ROOT);
        if (!List.of("USER", "ROUND_ROBIN", "QUEUE").contains(mode)) {
            throw new IllegalArgumentException("Assignment mode must be USER, ROUND_ROBIN or QUEUE.");
        }
        if ("USER".equals(mode) && request.targetUserId() == null) {
            throw new IllegalArgumentException("Pick the owner this rule assigns to, or change the mode.");
        }
        if ("QUEUE".equals(mode) && request.targetQueueId() == null) {
            throw new IllegalArgumentException("Pick the queue this rule assigns to, or change the mode.");
        }
        List<AssignmentMemberRequest> members = request.members() == null ? List.of() : request.members();
        if ("ROUND_ROBIN".equals(mode) && members.isEmpty()) {
            throw new IllegalArgumentException(
                    "A round-robin rule needs at least one owner in its pool, otherwise every lead it "
                            + "matches would fall through to the queue.");
        }
        UUID id = request.id();
        try {
            if (id == null) {
                id = jdbc.queryForObject("""
                        insert into leads.assignment_rule
                          (tenant_id, name, sort_order, active, match_territory, match_segment,
                           match_product_interest, match_source, match_min_score, assignment_mode,
                           target_user_id, target_queue_id, sla_policy_id)
                        values (?, ?, ?, coalesce(?, true), ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                        """, UUID.class, tenantId, request.name().trim(), request.sortOrder(), request.active(),
                        blankToNull(request.matchTerritory()), blankToNull(request.matchSegment()),
                        blankToNull(request.matchProductInterest()), blankToNull(request.matchSource()),
                        request.matchMinScore(), mode,
                        "USER".equals(mode) ? request.targetUserId() : null,
                        "QUEUE".equals(mode) ? request.targetQueueId() : null, request.slaPolicyId());
            } else {
                int updated = jdbc.update("""
                        update leads.assignment_rule
                        set name = ?, sort_order = ?, active = coalesce(?, active), match_territory = ?,
                            match_segment = ?, match_product_interest = ?, match_source = ?,
                            match_min_score = ?, assignment_mode = ?, target_user_id = ?, target_queue_id = ?,
                            sla_policy_id = ?, updated_at = now()
                        where tenant_id = ? and id = ?
                        """, request.name().trim(), request.sortOrder(), request.active(),
                        blankToNull(request.matchTerritory()), blankToNull(request.matchSegment()),
                        blankToNull(request.matchProductInterest()), blankToNull(request.matchSource()),
                        request.matchMinScore(), mode,
                        "USER".equals(mode) ? request.targetUserId() : null,
                        "QUEUE".equals(mode) ? request.targetQueueId() : null, request.slaPolicyId(),
                        tenantId, id);
                if (updated == 0) throw new NotFoundException("Assignment rule not found");
                jdbc.update("delete from leads.assignment_rule_member where tenant_id = ? and rule_id = ?",
                        tenantId, id);
            }
            for (AssignmentMemberRequest member : members) {
                jdbc.update("""
                        insert into leads.assignment_rule_member (tenant_id, rule_id, user_id, sort_order, capacity)
                        values (?, ?, ?, ?, ?)
                        """, tenantId, id, member.userId(), member.sortOrder(), member.capacity());
            }
            if ("ROUND_ROBIN".equals(mode)) {
                jdbc.update("""
                        insert into leads.assignment_cursor (tenant_id, rule_id) values (?, ?)
                        on conflict (tenant_id, rule_id) do nothing
                        """, tenantId, id);
            }
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Another assignment rule already uses that name or order position. "
                    + "Rule order decides which rule wins, so two rules cannot share a position.");
        }
        audit.record("LEAD_ASSIGNMENT_RULE_SAVE", "LEAD_CONFIG", id, "Saved assignment rule " + request.name(),
                Map.of("mode", mode, "order", request.sortOrder(), "members", members.size()));
        UUID finalId = id;
        return assignmentRules().stream().filter(r -> r.id().equals(finalId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Assignment rule not found after save"));
    }

    @Transactional
    public void deleteAssignmentRule(UUID id) {
        requireAdmin();
        UUID tenantId = TenantContext.get().tenantId();
        Integer inUse = jdbc.queryForObject(
                "select count(*) from crm.lead where tenant_id = ? and assignment_rule_id = ?",
                Integer.class, tenantId, id);
        if (inUse != null && inUse > 0) {
            // Deactivating keeps the audit trail of why each lead went where it
            // went. Deleting the rule would leave those leads pointing at nothing.
            jdbc.update("update leads.assignment_rule set active = false, updated_at = now() "
                    + "where tenant_id = ? and id = ?", tenantId, id);
            audit.record("LEAD_ASSIGNMENT_RULE_DEACTIVATE", "LEAD_CONFIG", id,
                    "Deactivated an assignment rule already recorded on " + inUse + " lead(s)",
                    Map.of("leadsRouted", inUse));
            return;
        }
        int deleted = jdbc.update("delete from leads.assignment_rule where tenant_id = ? and id = ?", tenantId, id);
        if (deleted == 0) throw new NotFoundException("Assignment rule not found");
        audit.record("LEAD_ASSIGNMENT_RULE_DELETE", "LEAD_CONFIG", id, "Deleted an assignment rule", Map.of("id", id));
    }

    public record SlaPolicyRequest(@NotNull UUID id, @Min(1) int firstResponseMinutes,
                                   UUID businessHoursId, UUID escalationUserId) {}

    @Transactional
    public SlaPolicyRow saveSlaPolicy(SlaPolicyRequest request) {
        requireAdmin();
        int updated = jdbc.update("""
                update leads.sla_policy
                set first_response_minutes = ?, business_hours_id = coalesce(?, business_hours_id),
                    escalation_user_id = coalesce(?, escalation_user_id), updated_at = now()
                where tenant_id = ? and id = ?
                """, request.firstResponseMinutes(), request.businessHoursId(), request.escalationUserId(),
                TenantContext.get().tenantId(), request.id());
        if (updated == 0) throw new NotFoundException("SLA policy not found");
        audit.record("LEAD_SLA_POLICY_SAVE", "LEAD_CONFIG", request.id(),
                "Set first-response target to " + request.firstResponseMinutes() + " working minutes",
                Map.of("minutes", request.firstResponseMinutes()));
        return slaPolicies().stream().filter(p -> p.id().equals(request.id())).findFirst()
                .orElseThrow(() -> new NotFoundException("SLA policy not found after save"));
    }

    public record BusinessHoursRequest(@NotNull UUID id, String timeZone,
                                       List<BusinessHoursDayRow> days, List<HolidayRow> holidays) {}

    /**
     * Edits business hours. Note what this does <em>not</em> do: it does not
     * recompute {@code first_response_due_at} on any existing lead. The SLA a
     * lead was given is the SLA it keeps (FR-LED-009, data model §4.3) — moving
     * the goalposts retroactively would make yesterday's breach report a
     * different number today.
     */
    @Transactional
    public BusinessHoursRow saveBusinessHours(BusinessHoursRequest request) {
        requireAdmin();
        UUID tenantId = TenantContext.get().tenantId();
        int updated = jdbc.update("""
                update leads.business_hours set time_zone = coalesce(?, time_zone), updated_at = now()
                where tenant_id = ? and id = ?
                """, blankToNull(request.timeZone()), tenantId, request.id());
        if (updated == 0) throw new NotFoundException("Business hours not found");
        if (request.days() != null) {
            jdbc.update("delete from leads.business_hours_day where tenant_id = ? and business_hours_id = ?",
                    tenantId, request.id());
            for (BusinessHoursDayRow day : request.days()) {
                jdbc.update("""
                        insert into leads.business_hours_day
                          (tenant_id, business_hours_id, day_of_week, open_time, close_time)
                        values (?, ?, ?, ?, ?)
                        """, tenantId, request.id(), day.dayOfWeek(), day.openTime(), day.closeTime());
            }
        }
        if (request.holidays() != null) {
            jdbc.update("delete from leads.business_hours_holiday where tenant_id = ? and business_hours_id = ?",
                    tenantId, request.id());
            for (HolidayRow holiday : request.holidays()) {
                jdbc.update("""
                        insert into leads.business_hours_holiday
                          (tenant_id, business_hours_id, holiday_date, name)
                        values (?, ?, ?, ?)
                        """, tenantId, request.id(), holiday.date(), holiday.name());
            }
        }
        audit.record("LEAD_BUSINESS_HOURS_SAVE", "LEAD_CONFIG", request.id(),
                "Updated business hours; response clocks already running are unchanged",
                Map.of("days", request.days() == null ? 0 : request.days().size(),
                        "holidays", request.holidays() == null ? 0 : request.holidays().size()));
        return businessHours().stream().filter(b -> b.id().equals(request.id())).findFirst()
                .orElseThrow(() -> new NotFoundException("Business hours not found after save"));
    }

    public record ConversionMappingRequest(@NotBlank String targetEntity, @NotBlank String sourceExpression,
                                           @NotBlank String targetField, Boolean customField,
                                           Integer sortOrder, Boolean active) {}

    @Transactional
    public ConversionMappingRow saveConversionMapping(ConversionMappingRequest request) {
        requireAdmin();
        String entity = request.targetEntity().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ACCOUNT", "CONTACT", "OPPORTUNITY").contains(entity)) {
            throw new IllegalArgumentException("Mapping target must be ACCOUNT, CONTACT or OPPORTUNITY.");
        }
        jdbc.update("""
                insert into leads.conversion_mapping (tenant_id, target_entity, source_expression, target_field,
                                                      custom_field, sort_order, active)
                values (?, ?, ?, ?, coalesce(?, false), coalesce(?, 100), coalesce(?, true))
                on conflict (tenant_id, target_entity, target_field) do update
                  set source_expression = excluded.source_expression, custom_field = excluded.custom_field,
                      sort_order = excluded.sort_order, active = excluded.active
                """, TenantContext.get().tenantId(), entity, request.sourceExpression().trim(),
                request.targetField().trim(), request.customField(), request.sortOrder(), request.active());
        audit.record("LEAD_CONVERSION_MAPPING_SAVE", "LEAD_CONFIG", null,
                "Mapped " + request.sourceExpression() + " to " + entity + "." + request.targetField(),
                Map.of("entity", entity, "target", request.targetField(), "source", request.sourceExpression()));
        return conversionMappings().stream()
                .filter(m -> m.targetEntity().equals(entity) && m.targetField().equals(request.targetField().trim()))
                .findFirst().orElseThrow(() -> new NotFoundException("Conversion mapping not found after save"));
    }

    @Transactional
    public void deleteConversionMapping(UUID id) {
        requireAdmin();
        int deleted = jdbc.update("delete from leads.conversion_mapping where tenant_id = ? and id = ?",
                TenantContext.get().tenantId(), id);
        if (deleted == 0) throw new NotFoundException("Conversion mapping not found");
        audit.record("LEAD_CONVERSION_MAPPING_DELETE", "LEAD_CONFIG", id, "Removed a conversion mapping",
                Map.of("id", id));
    }

    public record CaptureFormRequest(UUID id, @NotBlank String formKey, @NotBlank String name, Boolean active,
                                     String botProtection, String honeypotField, Integer minFillSeconds,
                                     List<String> requiredFields, Map<String, String> fieldMap,
                                     String defaultSource, String defaultStatus, String defaultCampaignCode,
                                     UUID defaultQueueId) {}

    @Transactional
    public CaptureFormRow saveCaptureForm(CaptureFormRequest request) {
        requireAdmin();
        UUID tenantId = TenantContext.get().tenantId();
        String formKey = request.formKey().trim().toLowerCase(Locale.ROOT);
        if (!formKey.matches("^[a-z0-9][a-z0-9-]{2,63}$")) {
            throw new IllegalArgumentException(
                    "The form key becomes part of a public URL: use 3-64 characters, lower case letters, "
                            + "digits and hyphens, starting with a letter or digit.");
        }
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(request.fieldMap() == null ? Map.of() : request.fieldMap());
        } catch (Exception ex) {
            throw new IllegalArgumentException("The field map could not be read as JSON.");
        }
        List<String> required = request.requiredFields() == null || request.requiredFields().isEmpty()
                ? List.of("firstName", "lastName", "company", "email")
                : request.requiredFields();
        try {
            jdbc.update("""
                    insert into leads.capture_form (tenant_id, form_key, name, active, bot_protection,
                        honeypot_field, min_fill_seconds, required_fields, field_map, default_source,
                        default_status, default_campaign_code, default_queue_id, updated_at)
                    values (?, ?, ?, coalesce(?, true), coalesce(?, 'BOTH'),
                            coalesce(?, 'company_website_confirm'), coalesce(?, 2), ?, ?::jsonb,
                            coalesce(?, 'WEB_FORM'), ?, ?, ?, now())
                    on conflict (tenant_id, form_key) do update
                      set name = excluded.name, active = excluded.active,
                          bot_protection = excluded.bot_protection, honeypot_field = excluded.honeypot_field,
                          min_fill_seconds = excluded.min_fill_seconds,
                          required_fields = excluded.required_fields, field_map = excluded.field_map,
                          default_source = excluded.default_source, default_status = excluded.default_status,
                          default_campaign_code = excluded.default_campaign_code,
                          default_queue_id = excluded.default_queue_id, updated_at = now()
                    """, tenantId, formKey, request.name().trim(), request.active(), request.botProtection(),
                    request.honeypotField(), request.minFillSeconds(), required.toArray(String[]::new), json,
                    request.defaultSource(), request.defaultStatus(), request.defaultCampaignCode(),
                    request.defaultQueueId());
            jdbc.update("""
                    insert into leads.capture_form_directory (form_key, tenant_id, active)
                    values (?, ?, coalesce(?, true))
                    on conflict (form_key) do update set active = excluded.active
                    where leads.capture_form_directory.tenant_id = excluded.tenant_id
                    """, formKey, tenantId, request.active());
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("That form key is already taken. Public form keys are unique across "
                    + "Axiom because they appear in the form's URL — pick another.");
        }
        audit.record("LEAD_CAPTURE_FORM_SAVE", "LEAD_CONFIG", null, "Saved web-to-lead form " + formKey,
                Map.of("formKey", formKey, "requiredFields", required));
        return captureForms().stream().filter(f -> f.formKey().equals(formKey)).findFirst()
                .orElseThrow(() -> new NotFoundException("Capture form not found after save"));
    }

    // ---------------------------------------------------------------- helpers

    /** The default open status a new lead lands in. */
    @Transactional(readOnly = true)
    public String defaultStatusCode() {
        List<String> codes = jdbc.queryForList("""
                select code from leads.lead_status
                where tenant_id = ? and active = true and category = 'OPEN'
                order by is_default desc, sort_order limit 1
                """, String.class, TenantContext.get().tenantId());
        return codes.isEmpty() ? "NEW" : codes.get(0);
    }

    /** The single status carrying a terminal category, e.g. CONVERTED. */
    @Transactional(readOnly = true)
    public String terminalStatusCode(String category) {
        List<String> codes = jdbc.queryForList("""
                select code from leads.lead_status where tenant_id = ? and category = ? order by sort_order limit 1
                """, String.class, TenantContext.get().tenantId(), category);
        return codes.isEmpty() ? category : codes.get(0);
    }

    /**
     * Validates a status code against the tenant's configured model, returning
     * its category. Refuses anything not configured — the model is
     * administrator-owned, so an unrecognised code is a mistake, not a new state.
     */
    @Transactional(readOnly = true)
    public String requireStatus(String code) {
        String normalized = normalizeCode(code);
        List<String> categories = jdbc.queryForList("""
                select category from leads.lead_status where tenant_id = ? and code = ? and active = true
                """, String.class, TenantContext.get().tenantId(), normalized);
        if (categories.isEmpty()) {
            String configured = String.join(", ", jdbc.queryForList(
                    "select code from leads.lead_status where tenant_id = ? and active = true order by sort_order",
                    String.class, TenantContext.get().tenantId()));
            throw new IllegalArgumentException("Status '" + normalized + "' is not in this workspace's lead status "
                    + "model. Configured statuses are: " + (configured.isEmpty() ? "none yet" : configured) + ".");
        }
        return categories.get(0);
    }

    @Transactional(readOnly = true)
    public String categoryOf(String code) {
        List<String> categories = jdbc.queryForList(
                "select category from leads.lead_status where tenant_id = ? and code = ?",
                String.class, TenantContext.get().tenantId(), normalizeCode(code));
        return categories.isEmpty() ? "OPEN" : categories.get(0);
    }

    @Transactional(readOnly = true)
    public UUID fallbackQueueId() {
        List<UUID> ids = jdbc.queryForList("""
                select id from leads.lead_queue where tenant_id = ? and active = true
                order by is_fallback desc, name limit 1
                """, UUID.class, TenantContext.get().tenantId());
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void validateOperator(String operator) {
        if (!RuleOperators.isSupported(operator)) {
            throw new IllegalArgumentException("Operator must be one of: "
                    + String.join(", ", RuleOperators.SUPPORTED) + ".");
        }
    }

    private void requireAdmin() {
        CrmRole.requireMasterAdmin(TenantContext.get().role());
    }

    static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
