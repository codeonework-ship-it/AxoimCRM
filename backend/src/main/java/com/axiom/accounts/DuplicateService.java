package com.axiom.accounts;

import com.axiom.audit.AuditService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FR-ACC-008 — duplicate detection on create and on update, across accounts,
 * contacts and leads, driven by rules held as tenant data.
 *
 * <p>Two things are deliberate. First, the rules live in {@code crm.duplicate_rule}
 * so a data steward re-tunes a threshold or flips warning-to-blocking without a
 * release. Second, the acting user always sees the candidates and their
 * confidence — a blocking rule refuses the write, but it refuses it while
 * showing its evidence, because a refusal a user cannot inspect is a refusal
 * they will route around.
 */
@Service
public class DuplicateService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public DuplicateService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    // --------------------------------------------------------------- contracts

    public record DuplicateRuleRow(UUID id, String ruleCode, String label, String entityType,
                                   String matchKind, String enforcement, double threshold, boolean active) {}

    /** What the caller is about to write. Any field may be blank. */
    public record Probe(@NotBlank String entityType, String name, String company, String email,
                        String phone, String website, UUID excludeId) {

        public String resolvedName() {
            return name == null || name.isBlank() ? company : name;
        }
    }

    public record RuleHit(String ruleCode, String label, String matchKind, String enforcement,
                          double confidence, double threshold, String matchedOn) {}

    public record MatchCandidate(String entityType, UUID id, String label, String context,
                                 double confidence, String strongestEnforcement, List<RuleHit> hits) {}

    /**
     * @param blocked           a blocking rule matched; the write must not proceed
     * @param warned            only warning rules matched; the write may proceed once acknowledged
     * @param topConfidence     highest confidence across all candidates
     */
    public record Assessment(boolean blocked, boolean warned, double topConfidence,
                             List<String> blockingRuleCodes, List<MatchCandidate> candidates,
                             List<String> rulesEvaluated) {

        public static Assessment clear(List<String> rulesEvaluated) {
            return new Assessment(false, false, 0d, List.of(), List.of(), rulesEvaluated);
        }
    }

    // ------------------------------------------------------------------- rules

    @Transactional(readOnly = true)
    public List<DuplicateRuleRow> rules(String entityType) {
        return jdbc.query("""
                select id, rule_code, label, entity_type, match_kind, enforcement, threshold, active
                from crm.duplicate_rule
                where tenant_id = ? and active = true and (? is null or entity_type = ?)
                order by enforcement, rule_code
                """, (rs, i) -> new DuplicateRuleRow(
                rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("label"),
                rs.getString("entity_type"), rs.getString("match_kind"), rs.getString("enforcement"),
                rs.getBigDecimal("threshold").doubleValue(), rs.getBoolean("active")),
                TenantContext.get().tenantId(), normalizeEntity(entityType), normalizeEntity(entityType));
    }

    // ---------------------------------------------------------------- assessing

    /** Candidate row pulled from one of the three searched entities. */
    private record Existing(String entityType, UUID id, String label, String context,
                            String companyName, String personName, String email,
                            String phone, String domain) {}

    @Transactional(readOnly = true)
    public Assessment assess(Probe probe) {
        String entity = normalizeEntity(probe.entityType());
        List<DuplicateRuleRow> active = rules(entity);
        List<String> evaluated = active.stream().map(DuplicateRuleRow::ruleCode).toList();
        if (active.isEmpty()) return Assessment.clear(evaluated);

        Map<String, Existing> pool = new LinkedHashMap<>();
        for (Existing row : searchPool(entity, probe)) {
            pool.putIfAbsent(row.entityType() + ":" + row.id(), row);
        }
        if (pool.isEmpty()) return Assessment.clear(evaluated);

        String probeName = probe.resolvedName();
        String probeEmail = DuplicateMatcher.normalizeEmail(probe.email());
        String probePhone = DuplicateMatcher.normalizePhone(probe.phone());
        String probeDomain = DuplicateMatcher.normalizeDomain(
                probe.website() != null && !probe.website().isBlank() ? probe.website() : probe.email());

        List<MatchCandidate> candidates = new ArrayList<>();
        for (Existing row : pool.values()) {
            List<RuleHit> hits = new ArrayList<>();
            for (DuplicateRuleRow rule : active) {
                double confidence = score(rule, row, probeName, probeEmail, probePhone, probeDomain);
                if (confidence >= rule.threshold() && confidence > 0d) {
                    hits.add(new RuleHit(rule.ruleCode(), rule.label(), rule.matchKind(),
                            rule.enforcement(), DuplicateMatcher.round(confidence), rule.threshold(),
                            describeMatch(rule.matchKind(), row)));
                }
            }
            if (hits.isEmpty()) continue;
            double best = hits.stream().mapToDouble(RuleHit::confidence).max().orElse(0d);
            String enforcement = hits.stream().anyMatch(h -> "BLOCKING".equals(h.enforcement()))
                    ? "BLOCKING" : "WARNING";
            candidates.add(new MatchCandidate(row.entityType(), row.id(), row.label(), row.context(),
                    DuplicateMatcher.round(best), enforcement, hits));
        }
        if (candidates.isEmpty()) return Assessment.clear(evaluated);

        candidates.sort(Comparator.comparingDouble(MatchCandidate::confidence).reversed());
        Set<String> blocking = new LinkedHashSet<>();
        candidates.forEach(c -> c.hits().stream()
                .filter(h -> "BLOCKING".equals(h.enforcement()))
                .forEach(h -> blocking.add(h.ruleCode())));
        double top = candidates.get(0).confidence();
        return new Assessment(!blocking.isEmpty(), blocking.isEmpty(), top,
                List.copyOf(blocking), candidates, evaluated);
    }

    private double score(DuplicateRuleRow rule, Existing row, String probeName,
                         String probeEmail, String probePhone, String probeDomain) {
        return switch (rule.matchKind()) {
            case "NAME_FUZZY" -> DuplicateMatcher.nameConfidence(probeName,
                    "CONTACT".equals(row.entityType()) ? row.personName() : row.companyName());
            case "COMPANY_FUZZY" -> DuplicateMatcher.nameConfidence(probeName, row.companyName());
            case "EMAIL_EXACT" -> DuplicateMatcher.exactConfidence(probeEmail,
                    DuplicateMatcher.normalizeEmail(row.email()));
            case "EMAIL_LOCAL_FUZZY" -> DuplicateMatcher.nameConfidence(
                    localPart(probeEmail), localPart(DuplicateMatcher.normalizeEmail(row.email())));
            case "DOMAIN_EXACT" -> DuplicateMatcher.exactConfidence(probeDomain,
                    DuplicateMatcher.normalizeDomain(row.domain()));
            case "PHONE_NORMALIZED" -> probePhone.length() < 7 ? 0d
                    : DuplicateMatcher.exactConfidence(probePhone, DuplicateMatcher.normalizePhone(row.phone()));
            default -> 0d;
        };
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static String describeMatch(String matchKind, Existing row) {
        return switch (matchKind) {
            case "NAME_FUZZY", "COMPANY_FUZZY" -> "name";
            case "EMAIL_EXACT", "EMAIL_LOCAL_FUZZY" -> "email " + nullSafe(row.email());
            case "DOMAIN_EXACT" -> "web domain " + nullSafe(row.domain());
            case "PHONE_NORMALIZED" -> "telephone " + nullSafe(row.phone());
            default -> matchKind;
        };
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    // ------------------------------------------------------------------- pools

    /**
     * A duplicate probe never scans a whole tenant. Each search below is
     * narrowed by a cheap indexable predicate first — a shared name token, an
     * exact domain, an exact email or a normalized telephone — and the fuzzy
     * arithmetic runs only over what survives.
     */
    private List<Existing> searchPool(String entity, Probe probe) {
        List<Existing> found = new ArrayList<>();
        boolean searchAccounts = !"CONTACT".equals(entity);
        boolean searchContacts = !"ACCOUNT".equals(entity);
        if (searchAccounts) found.addAll(accountCandidates(probe));
        if (searchContacts) found.addAll(contactCandidates(probe));
        found.addAll(leadCandidates(probe));
        return found;
    }

    /**
     * A Postgres {@code text[]} literal of {@code %token%} patterns.
     *
     * <p>Passed as a single string and cast in SQL rather than bound as a Java
     * array: the JDBC driver will not map {@code String[]} onto {@code text[]}
     * without an explicit {@code createArrayOf}, and the literal form keeps this
     * a plain prepared-statement parameter. Safe because {@link
     * DuplicateMatcher#normalizeName} has already reduced the value to
     * lower-case letters, digits and single spaces — nothing an array literal
     * treats as punctuation survives.
     */
    private String nameTokenArray(String name) {
        String normalized = DuplicateMatcher.normalizeName(name);
        if (normalized.isEmpty()) return "{}";
        List<String> tokens = java.util.Arrays.stream(normalized.split(" "))
                .filter(token -> token.length() >= 3)
                .map(token -> "%" + token + "%")
                .distinct()
                .limit(4)
                .toList();
        return tokens.isEmpty() ? "{}" : "{" + String.join(",", tokens) + "}";
    }

    private List<Existing> accountCandidates(Probe probe) {
        String tokens = nameTokenArray(probe.resolvedName());
        String phone = DuplicateMatcher.normalizePhone(probe.phone());
        String domain = DuplicateMatcher.normalizeDomain(
                probe.website() != null && !probe.website().isBlank() ? probe.website() : probe.email());
        return jdbc.query("""
                select id, name, coalesce(email_domain, '') as email_domain,
                       coalesce(phone, '') as phone, coalesce(website, '') as website,
                       coalesce(industry, '') as industry
                from crm.account
                where tenant_id = ? and deleted_at is null and status <> 'MERGED'
                  and (?::uuid is null or id <> ?::uuid)
                  and ( (cardinality(?::text[]) > 0 and lower(name) like any (?::text[]))
                     or (? <> '' and lower(coalesce(email_domain, '')) = ?)
                     or (length(?) >= 7 and right(regexp_replace(coalesce(phone, ''), '[^0-9]', '', 'g'), 10) = ?) )
                order by name
                limit 50
                """, (rs, i) -> new Existing("ACCOUNT", rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString("industry").isBlank() ? "Account" : "Account - " + rs.getString("industry"),
                        rs.getString("name"), rs.getString("name"), null,
                        rs.getString("phone"),
                        rs.getString("email_domain").isBlank() ? rs.getString("website") : rs.getString("email_domain")),
                TenantContext.get().tenantId(), probe.excludeId(), probe.excludeId(),
                tokens, tokens, domain, domain, phone, phone);
    }

    private List<Existing> contactCandidates(Probe probe) {
        String tokens = nameTokenArray(probe.resolvedName());
        String email = DuplicateMatcher.normalizeEmail(probe.email());
        String phone = DuplicateMatcher.normalizePhone(probe.phone());
        String domain = DuplicateMatcher.normalizeDomain(probe.email());
        return jdbc.query("""
                select c.id, c.first_name, c.last_name, coalesce(c.email, '') as email,
                       coalesce(c.phone, '') as phone, coalesce(c.mobile, '') as mobile,
                       coalesce(a.name, '') as account_name
                from crm.contact c
                left join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
                where c.tenant_id = ? and c.deleted_at is null and c.status <> 'MERGED'
                  and (?::uuid is null or c.id <> ?::uuid)
                  and ( (cardinality(?::text[]) > 0
                         and lower(c.first_name || ' ' || c.last_name) like any (?::text[]))
                     or (? <> '' and lower(coalesce(c.email, '')) = ?)
                     or (? <> '' and lower(coalesce(c.email, '')) like '%@' || ?)
                     or (length(?) >= 7 and right(regexp_replace(coalesce(c.phone, '') || coalesce(c.mobile, ''), '[^0-9]', '', 'g'), 10) = ?) )
                order by c.last_name, c.first_name
                limit 50
                """, (rs, i) -> {
                    String full = (rs.getString("first_name") + " " + rs.getString("last_name")).trim();
                    String mail = rs.getString("email");
                    return new Existing("CONTACT", rs.getObject("id", UUID.class), full,
                            rs.getString("account_name").isBlank() ? "Contact" : "Contact at " + rs.getString("account_name"),
                            rs.getString("account_name"), full, mail,
                            rs.getString("phone").isBlank() ? rs.getString("mobile") : rs.getString("phone"),
                            DuplicateMatcher.normalizeDomain(mail));
                },
                TenantContext.get().tenantId(), probe.excludeId(), probe.excludeId(),
                tokens, tokens, email, email, domain, domain, phone, phone);
    }

    /**
     * Leads are searched with the columns guaranteed by the baseline schema only.
     * E05 has since widened the lead record, but this module does not reach into
     * another epic's newer columns — a cross-module read that outruns its own
     * migration is how a shared build breaks for everyone.
     */
    private List<Existing> leadCandidates(Probe probe) {
        String tokens = nameTokenArray(probe.resolvedName());
        String email = DuplicateMatcher.normalizeEmail(probe.email());
        String domain = DuplicateMatcher.normalizeDomain(
                probe.website() != null && !probe.website().isBlank() ? probe.website() : probe.email());
        return jdbc.query("""
                select id, first_name, last_name, company, coalesce(email, '') as email, status
                from crm.lead
                where tenant_id = ? and deleted_at is null and status <> 'CONVERTED'
                  and (?::uuid is null or id <> ?::uuid)
                  and ( (cardinality(?::text[]) > 0
                         and (lower(company) like any (?::text[])
                              or lower(first_name || ' ' || last_name) like any (?::text[])))
                     or (? <> '' and lower(coalesce(email, '')) = ?)
                     or (? <> '' and lower(coalesce(email, '')) like '%@' || ?) )
                order by company
                limit 50
                """, (rs, i) -> {
                    String full = (rs.getString("first_name") + " " + rs.getString("last_name")).trim();
                    String mail = rs.getString("email");
                    return new Existing("LEAD", rs.getObject("id", UUID.class),
                            full + " - " + rs.getString("company"),
                            "Lead (" + rs.getString("status") + ")",
                            rs.getString("company"), full, mail, null,
                            DuplicateMatcher.normalizeDomain(mail));
                },
                TenantContext.get().tenantId(), probe.excludeId(), probe.excludeId(),
                tokens, tokens, tokens, email, email, domain, domain);
    }

    // --------------------------------------------------------------- decisions

    /**
     * Records what the acting user did about the candidates they were shown. A
     * warning rule is only worth having if the override is on the record.
     */
    @Transactional
    public void recordDecision(String entityType, UUID entityId, String operation,
                               String decision, Assessment assessment, String reason) {
        String candidateJson;
        try {
            candidateJson = json.writeValueAsString(assessment.candidates());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Duplicate candidates could not be serialized", ex);
        }
        jdbc.update("""
                insert into crm.duplicate_decision
                  (tenant_id, entity_type, entity_id, operation, decision, rule_code,
                   candidate_json, top_confidence, decided_by, reason)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """, TenantContext.get().tenantId(), normalizeEntity(entityType), entityId,
                operation, decision,
                assessment.blockingRuleCodes().isEmpty() ? null : assessment.blockingRuleCodes().get(0),
                candidateJson, assessment.topConfidence(), TenantContext.get().userId(), reason);

        audit.record("DUPLICATE_" + decision, normalizeEntity(entityType), entityId,
                "Duplicate check " + decision.toLowerCase(Locale.ROOT) + " on " + operation.toLowerCase(Locale.ROOT)
                        + " with top confidence " + assessment.topConfidence(),
                Map.of("operation", operation,
                        "decision", decision,
                        "topConfidence", assessment.topConfidence(),
                        "blockingRules", assessment.blockingRuleCodes(),
                        "candidateIds", assessment.candidates().stream().map(c -> c.id().toString()).toList()));
    }

    static String normalizeEntity(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
