package com.axiom.analytics;

import com.axiom.common.NotFoundException;
import com.axiom.security.SecurableObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The registry of reportable datasets, and the ONLY place a user-supplied field
 * name becomes a SQL identifier.
 *
 * <h2>Why an enum and not a table</h2>
 * The same argument {@link SecurableObject} makes, and it matters more here. A
 * report builder is by definition a surface where users type field names, group
 * keys and aggregate functions, and every one of those is interpolated into SQL
 * because none of them can be bound as a parameter. If the registry lived in a
 * tenant-writable table, SQL injection would be one admin screen away. This enum
 * is a small closed set a code review can check, and {@link #field(String)}
 * throws rather than guessing.
 *
 * <h2>The fact table is the query target; the source table is the authority</h2>
 * {@code factTable} is what reports read (ADR-008 decision 3 — analytical queries
 * never touch OLTP tables). {@code securable} names the authoritative object that
 * decides <em>visibility</em>, which the projection never does (decision 4). The
 * two are separate fields here because conflating them is exactly the mistake the
 * ADR warns about.
 *
 * <h2>Adding a reportable field</h2>
 * ADR-008 states the cost honestly: "adding a reportable field means changing the
 * projection as well as the entity — a second place to forget." A new field needs
 * three edits, all in this module: the column in V2xx, the projection SQL in
 * {@link ReadModelProjector}, and one line here. Anything missing one of the
 * three fails loudly rather than reporting a silently empty column.
 */
public enum AnalyticsDataset {

    OPPORTUNITY("Opportunities", "analytics.opportunity_fact", "opportunity_id",
            SecurableObject.OPPORTUNITY, "sales.opportunity", false, "opportunity",
            "/pipeline?focus=",
            fields(
                    field("opportunityId", "opportunity_id", Kind.ID),
                    field("name", "name", Kind.TEXT),
                    field("accountId", "account_id", Kind.ID),
                    field("accountName", "account_name", Kind.TEXT),
                    field("accountIndustry", "account_industry", Kind.TEXT),
                    field("accountSegment", "account_segment", Kind.TEXT),
                    field("accountTerritory", "account_territory", Kind.TEXT),
                    field("ownerId", "owner_id", Kind.ID),
                    field("ownerName", "owner_name", Kind.TEXT),
                    field("stageName", "stage_name", Kind.TEXT),
                    field("stageSortOrder", "stage_sort_order", Kind.NUMBER),
                    field("forecastCategory", "forecast_category", Kind.TEXT),
                    field("recordType", "record_type", Kind.TEXT),
                    field("currencyCode", "currency_code", Kind.TEXT),
                    field("amount", "amount", Kind.MONEY),
                    field("weightedAmount", "weighted_amount", Kind.MONEY),
                    field("recurringAmount", "recurring_amount", Kind.MONEY),
                    field("oneTimeAmount", "one_time_amount", Kind.MONEY),
                    field("acv", "acv", Kind.MONEY),
                    field("arr", "arr", Kind.MONEY),
                    field("tcv", "tcv", Kind.MONEY),
                    field("termMonths", "term_months", Kind.NUMBER),
                    field("probability", "probability", Kind.NUMBER),
                    field("closeDate", "close_date", Kind.DATE),
                    field("originalCloseDate", "original_close_date", Kind.DATE),
                    field("createdOn", "created_on", Kind.DATE),
                    field("closedAt", "closed_at", Kind.TIMESTAMP),
                    field("isClosed", "is_closed", Kind.BOOLEAN),
                    field("isWon", "is_won", Kind.BOOLEAN),
                    field("closeOutcome", "close_outcome", Kind.TEXT),
                    field("slipCount", "slip_count", Kind.NUMBER),
                    field("cumulativeSlipDays", "cumulative_slip_days", Kind.NUMBER),
                    field("ageDays", "age_days", Kind.NUMBER),
                    field("cycleDays", "cycle_days", Kind.NUMBER))),

    LEAD("Leads", "analytics.lead_fact", "lead_id",
            SecurableObject.LEAD, "crm.lead", true, "lead",
            "/leads?focus=",
            fields(
                    field("leadId", "lead_id", Kind.ID),
                    field("fullName", "full_name", Kind.TEXT),
                    field("company", "company", Kind.TEXT),
                    field("ownerId", "owner_id", Kind.ID),
                    field("ownerName", "owner_name", Kind.TEXT),
                    field("status", "status", Kind.TEXT),
                    field("statusCategory", "status_category", Kind.TEXT),
                    field("rating", "rating", Kind.TEXT),
                    field("source", "source", Kind.TEXT),
                    field("campaignCode", "campaign_code", Kind.TEXT),
                    field("territory", "territory", Kind.TEXT),
                    field("segment", "segment", Kind.TEXT),
                    field("score", "score", Kind.NUMBER),
                    field("createdOn", "created_on", Kind.DATE),
                    field("convertedAt", "converted_at", Kind.TIMESTAMP),
                    field("disqualifiedAt", "disqualified_at", Kind.TIMESTAMP),
                    field("disqualificationReason", "disqualification_reason", Kind.TEXT),
                    field("isConverted", "is_converted", Kind.BOOLEAN),
                    field("isDisqualified", "is_disqualified", Kind.BOOLEAN),
                    field("slaBreached", "sla_breached", Kind.BOOLEAN),
                    field("firstResponseMinutes", "first_response_minutes", Kind.NUMBER))),

    /**
     * Activities have no {@link SecurableObject} of their own in the registry, so
     * {@code securable} is null and {@link ReportAccessScope} falls back to
     * "records I own, or records on an account I may read". That is deliberately
     * the conservative reading: an activity whose account this caller cannot read
     * is excluded even if the underlying activity table would have allowed it.
     */
    ACTIVITY("Activities", "analytics.activity_fact", "activity_id",
            null, "engagement.activity", true, "activity",
            "/activities?focus=",
            fields(
                    field("activityId", "activity_id", Kind.ID),
                    field("subject", "subject", Kind.TEXT),
                    field("activityType", "activity_type", Kind.TEXT),
                    field("status", "status", Kind.TEXT),
                    field("direction", "direction", Kind.TEXT),
                    field("outcome", "outcome", Kind.TEXT),
                    field("ownerId", "owner_id", Kind.ID),
                    field("ownerName", "owner_name", Kind.TEXT),
                    field("relatedEntityType", "related_entity_type", Kind.TEXT),
                    field("accountId", "account_id", Kind.ID),
                    field("accountName", "account_name", Kind.TEXT),
                    field("occurredOn", "occurred_on", Kind.DATE),
                    field("occurredAt", "occurred_at", Kind.TIMESTAMP),
                    field("completedAt", "completed_at", Kind.TIMESTAMP),
                    field("durationMinutes", "duration_minutes", Kind.NUMBER),
                    field("isCompleted", "is_completed", Kind.BOOLEAN))),

    ACCOUNT("Accounts", "analytics.account_fact", "account_id",
            SecurableObject.ACCOUNT, "crm.account", true, "account",
            "/accounts?focus=",
            fields(
                    field("accountId", "account_id", Kind.ID),
                    field("name", "name", Kind.TEXT),
                    field("industry", "industry", Kind.TEXT),
                    field("segment", "segment", Kind.TEXT),
                    field("territory", "territory", Kind.TEXT),
                    field("businessUnit", "business_unit", Kind.TEXT),
                    field("status", "status", Kind.TEXT),
                    field("ownerId", "owner_id", Kind.ID),
                    field("ownerName", "owner_name", Kind.TEXT),
                    field("healthScore", "health_score", Kind.NUMBER),
                    field("healthBand", "health_band", Kind.TEXT),
                    field("annualRevenue", "annual_revenue", Kind.MONEY),
                    field("employeeCount", "employee_count", Kind.NUMBER),
                    field("contactCount", "contact_count", Kind.NUMBER),
                    field("openOpportunityCount", "open_opportunity_count", Kind.NUMBER),
                    field("openPipelineAmount", "open_pipeline_amount", Kind.MONEY),
                    field("wonAmount", "won_amount", Kind.MONEY),
                    field("activityCount", "activity_count", Kind.NUMBER),
                    field("lastActivityAt", "last_activity_at", Kind.TIMESTAMP),
                    field("createdOn", "created_on", Kind.DATE)));

    /** How a field may be used: what can be grouped, what can be summed, how it renders. */
    public enum Kind {
        ID(false, false), TEXT(true, false), NUMBER(true, true), MONEY(false, true),
        DATE(true, false), TIMESTAMP(false, false), BOOLEAN(true, false);

        private final boolean groupable;
        private final boolean summable;

        Kind(boolean groupable, boolean summable) {
            this.groupable = groupable;
            this.summable = summable;
        }

        public boolean groupable() { return groupable; }
        public boolean summable() { return summable; }
    }

    public record Field(String apiName, String column, Kind kind) {}

    private final String label;
    private final String factTable;
    private final String idColumn;
    private final SecurableObject securable;
    private final String sourceTable;
    private final boolean sourceSoftDeleted;
    private final String aggregateType;
    private final String routePrefix;
    private final Map<String, Field> fields;

    AnalyticsDataset(String label, String factTable, String idColumn, SecurableObject securable,
                     String sourceTable, boolean sourceSoftDeleted, String aggregateType,
                     String routePrefix, Map<String, Field> fields) {
        this.label = label;
        this.factTable = factTable;
        this.idColumn = idColumn;
        this.securable = securable;
        this.sourceTable = sourceTable;
        this.sourceSoftDeleted = sourceSoftDeleted;
        this.aggregateType = aggregateType;
        this.routePrefix = routePrefix;
        this.fields = fields;
    }

    public String label() { return label; }

    /** The read-model table every report query runs against. */
    public String factTable() { return factTable; }

    /** The fact table's business key — the id a drill-through resolves against. */
    public String idColumn() { return idColumn; }

    /** The authoritative object that decides visibility, or empty where none is registered. */
    public Optional<SecurableObject> securable() { return Optional.ofNullable(securable); }

    /** The OLTP table the projection reads. Never queried by a report. */
    public String sourceTable() { return sourceTable; }

    public boolean sourceSoftDeleted() { return sourceSoftDeleted; }

    /** The {@code outbox_event.aggregate_type} whose events feed this projection (ADR-003). */
    public String aggregateType() { return aggregateType; }

    /** Front-end route for a drill-through target. */
    public String routePrefix() { return routePrefix; }

    public List<Field> fields() { return List.copyOf(fields.values()); }

    public Set<String> fieldNames() { return fields.keySet(); }

    public boolean hasField(String apiName) { return apiName != null && fields.containsKey(apiName); }

    /**
     * @throws IllegalArgumentException naming the queryable fields — a report
     *         builder error must tell the author what they <em>can</em> ask for,
     *         not merely that they asked wrong.
     */
    public Field field(String apiName) {
        Field found = apiName == null ? null : fields.get(apiName);
        if (found == null) {
            throw new IllegalArgumentException("'" + apiName + "' is not a reportable field of "
                    + name() + ". Reportable fields: " + String.join(", ", fields.keySet()));
        }
        return found;
    }

    public String column(String apiName) { return field(apiName).column(); }

    public static AnalyticsDataset of(String value) {
        if (value == null || value.isBlank()) throw new NotFoundException("A dataset is required");
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException("Unknown dataset: " + value);
        }
    }

    public static Optional<AnalyticsDataset> forAggregateType(String aggregateType) {
        if (aggregateType == null) return Optional.empty();
        String needle = aggregateType.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(d -> d.aggregateType.equals(needle)).findFirst();
    }

    private static Field field(String apiName, String column, Kind kind) {
        return new Field(apiName, column, kind);
    }

    /**
     * Insertion order is preserved on purpose — it is the column order a report
     * with no explicit selection gets, and the order the field picker lists. A
     * {@code Map.copyOf} here would be unordered and the UI would reshuffle its
     * field list between JVM runs for no reason a user could explain.
     */
    private static Map<String, Field> fields(Field... entries) {
        Map<String, Field> map = new LinkedHashMap<>();
        for (Field entry : entries) map.put(entry.apiName(), entry);
        return java.util.Collections.unmodifiableMap(map);
    }
}
