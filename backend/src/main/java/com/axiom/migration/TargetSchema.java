package com.axiom.migration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Axiom's side of the mapping: which entities a migration can write to and which
 * fields on each are writable, in our vocabulary.
 *
 * <p>This is the half of the anti-corruption layer that faces inward. The
 * adapter says what Salesforce calls things; this says what Axiom calls things;
 * {@link MappingProposer} is the only place the two meet, and the operator gets
 * to overrule it. Nothing else in the module needs to know that
 * {@code NumberOfEmployees} and {@code employeeCount} are the same idea.
 *
 * <p>Declared as data rather than derived from the database because a migration
 * must not be able to write to a column simply because it exists: {@code version},
 * {@code deleted_at}, {@code hierarchy_path} and the health-score columns are all
 * real columns that an import has no business setting. The allow-list is the
 * point.
 *
 * <p>{@code money} marks the fields whose sums the reconciliation report compares
 * (FR-MIG-006). {@code referenceTo} marks the fields that carry a relationship,
 * which is what lets an unresolvable one be reported with both endpoints named
 * (FR-MIG-004) instead of being written as null.
 */
public final class TargetSchema {

    private TargetSchema() {}

    public static final String ACCOUNT = "ACCOUNT";
    public static final String CONTACT = "CONTACT";
    public static final String LEAD = "LEAD";
    public static final String OPPORTUNITY = "OPPORTUNITY";
    public static final String ACTIVITY = "ACTIVITY";

    /**
     * @param name        the Axiom field name used in mapping rows and the API
     * @param column      the physical column written by the importer
     * @param type        TEXT | NUMBER | MONEY | DATE | DATETIME | BOOLEAN | REFERENCE
     * @param required    a record missing this fails validation with a named reason
     * @param money       included in reconciliation monetary sums
     * @param referenceTo the target entity this field points at, or null
     * @param aliases     source field names, normalised, that propose onto this field
     */
    public record TargetField(String name, String column, String type, boolean required,
                              boolean money, String referenceTo, List<String> aliases) {}

    public record TargetEntity(String name, String label, String table, List<TargetField> fields) {

        public Optional<TargetField> field(String fieldName) {
            return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
        }

        public List<TargetField> required() {
            return fields.stream().filter(TargetField::required).toList();
        }
    }

    private static TargetField f(String name, String column, String type, String... aliases) {
        return new TargetField(name, column, type, false, false, null, List.of(aliases));
    }

    private static TargetField req(String name, String column, String type, String... aliases) {
        return new TargetField(name, column, type, true, false, null, List.of(aliases));
    }

    private static TargetField money(String name, String column, String... aliases) {
        return new TargetField(name, column, "MONEY", false, true, null, List.of(aliases));
    }

    private static TargetField ref(String name, String column, String referenceTo, boolean required, String... aliases) {
        return new TargetField(name, column, "REFERENCE", required, false, referenceTo, List.of(aliases));
    }

    private static final Map<String, TargetEntity> ENTITIES = new LinkedHashMap<>();

    static {
        put(new TargetEntity(ACCOUNT, "Account", "crm.account", List.of(
                req("name", "name", "TEXT", "name", "accountname", "companyname", "company"),
                f("legalName", "legal_name", "TEXT", "legalname", "registeredname"),
                f("accountNumber", "account_number", "TEXT", "accountnumber", "customernumber", "acctnum"),
                f("industry", "industry", "TEXT", "industry", "sector", "verticals"),
                f("website", "website", "TEXT", "website", "domain", "url", "homepage"),
                f("phone", "phone", "TEXT", "phone", "mainphone", "telephone"),
                f("emailDomain", "email_domain", "TEXT", "emaildomain"),
                money("annualRevenue", "annual_revenue", "annualrevenue", "revenue", "turnover"),
                f("employeeCount", "employee_count", "INTEGER", "numberofemployees", "employeecount", "employees", "headcount"),
                f("segment", "segment", "TEXT", "segment", "tier", "customersegment"),
                f("territory", "territory", "TEXT", "territory", "region", "salesregion"),
                f("businessUnit", "business_unit", "TEXT", "businessunit", "division"),
                f("currencyCode", "currency_code", "CURRENCY_CODE", "currencyisocode", "currency", "currencycode"),
                f("taxId", "tax_id", "TEXT", "taxid", "vatnumber", "gstin"),
                ref("parentAccountId", "parent_account_id", ACCOUNT, false, "parentid", "parentaccount", "parentaccountid"))));

        put(new TargetEntity(CONTACT, "Contact", "crm.contact", List.of(
                req("firstName", "first_name", "TEXT", "firstname", "givenname", "forename"),
                req("lastName", "last_name", "TEXT", "lastname", "surname", "familyname"),
                f("email", "email", "TEXT", "email", "emailaddress", "primaryemail"),
                f("title", "title", "TEXT", "title", "jobtitle", "position"),
                f("phone", "phone", "TEXT", "phone", "workphone", "telephone"),
                f("mobile", "mobile", "TEXT", "mobilephone", "mobile", "cell"),
                f("department", "department", "TEXT", "department", "team"),
                f("seniority", "seniority", "SENIORITY", "seniority", "level"),
                ref("accountId", "account_id", ACCOUNT, false, "accountid", "account", "companyid"),
                ref("reportsToContactId", "reports_to_contact_id", CONTACT, false, "reportstoid", "managerid", "reportsto"))));

        put(new TargetEntity(LEAD, "Lead", "crm.lead", List.of(
                req("firstName", "first_name", "TEXT", "firstname", "givenname"),
                req("lastName", "last_name", "TEXT", "lastname", "surname"),
                req("company", "company", "TEXT", "company", "companyname", "organisation", "organization"),
                f("email", "email", "TEXT", "email", "emailaddress"),
                f("phone", "phone", "TEXT", "phone", "telephone"),
                f("title", "title", "TEXT", "title", "jobtitle"),
                f("source", "source", "TEXT", "leadsource", "source", "origin"),
                f("rating", "rating", "TEXT", "rating", "leadrating"),
                f("territory", "territory", "TEXT", "territory", "region"),
                f("segment", "segment", "TEXT", "segment"),
                f("campaignCode", "campaign_code", "TEXT", "campaigncode", "campaign"))));

        put(new TargetEntity(OPPORTUNITY, "Opportunity", "sales.opportunity", List.of(
                req("name", "name", "TEXT", "name", "opportunityname", "dealname"),
                money("amount", "amount", "amount", "value", "dealvalue", "totalvalue"),
                f("currencyCode", "currency_code", "CURRENCY_CODE", "currencyisocode", "currency", "currencycode"),
                f("closeDate", "close_date", "DATE", "closedate", "expectedclosedate", "estimatedclose"),
                f("stageName", "stage_id", "TEXT", "stagename", "stage", "dealstage"),
                f("probability", "probability", "NUMBER", "probability", "winprobability"),
                f("nextStep", "next_step", "TEXT", "nextstep", "nextaction"),
                f("forecastCategory", "forecast_category", "TEXT", "forecastcategory", "forecastcategoryname"),
                ref("accountId", "account_id", ACCOUNT, true, "accountid", "account", "companyid"),
                ref("primaryContactId", "primary_contact_id", CONTACT, false, "primarycontactid", "contactid", "primarycontact"))));

        // History migrates as NOTE activities. engagement.activity CHECKs make
        // TASK require a due date and CALL require direction, duration and
        // disposition — none of which a migrated historical record has. Writing
        // a NOTE with the source's own timestamp is a complete record of what
        // happened; inventing a duration to satisfy a constraint would not be.
        // The importer states this as an INFO issue on every run rather than
        // leaving the operator to infer it.
        put(new TargetEntity(ACTIVITY, "Activity history", "engagement.activity", List.of(
                req("subject", "subject", "TEXT", "subject", "title", "summary"),
                f("body", "body", "TEXT", "body", "description", "notes", "note", "comments"),
                f("occurredAt", "occurred_at", "DATETIME", "loggedat", "occurredat", "activitydate", "timestamp"),
                // column null: preserved as a recorded value on the migration ledger
                // (FR-MIG-005), never resolved onto an Axiom user who may not exist.
                f("actor", null, "TEXT", "loggedbyname", "createdbyname", "actor"),
                ref("relatedAccountId", "related_entity_id", ACCOUNT, true,
                        "relatedaccountid", "whatid", "accountid", "parentid", "relatedto"))));
    }

    private static void put(TargetEntity entity) {
        ENTITIES.put(entity.name(), entity);
    }

    public static List<TargetEntity> entities() {
        return List.copyOf(ENTITIES.values());
    }

    public static Optional<TargetEntity> entity(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(ENTITIES.get(name.toUpperCase(Locale.ROOT)));
    }

    /** Order in which entities must be written so a reference always resolves. */
    public static List<String> writeOrder() {
        return List.of(ACCOUNT, CONTACT, LEAD, OPPORTUNITY, ACTIVITY);
    }

    /** Reverse of {@link #writeOrder()} — the only safe order to delete in. */
    public static List<String> deleteOrder() {
        return List.of(ACTIVITY, OPPORTUNITY, LEAD, CONTACT, ACCOUNT);
    }

    /**
     * Normalise a field name for alias matching: {@code Legacy_Region__c} and
     * {@code legacyRegion} collapse to the same token so the proposer is not
     * defeated by naming convention alone.
     */
    public static String normalise(String raw) {
        if (raw == null) return "";
        String s = raw;
        if (s.endsWith("__c") || s.endsWith("__C")) s = s.substring(0, s.length() - 3);
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
