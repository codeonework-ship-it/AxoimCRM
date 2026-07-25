package com.axiom.leads;

import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Duplicate detection across leads, contacts and accounts (FR-LED-004) and
 * lead-to-account matching by domain and name similarity (FR-LED-005).
 *
 * <p>Both requirements are one search with two consumers, so they live together:
 * the same candidate set decides whether an inbound lead is a duplicate and which
 * account it probably belongs to. Splitting them would mean two definitions of
 * "same company" that drift apart.
 *
 * <p>Nothing here decides anything. It returns candidates with a confidence and a
 * stated basis; the policy in {@link LeadIngestionService} decides what to do,
 * and an account match is only linked to a lead after a human confirms it — which
 * is what FR-LED-005's "presented with confidence for confirmation" requires.
 */
@Service
public class LeadMatchingService {

    /** Free-mail domains are useless for account matching: everyone shares them. */
    private static final List<String> CONSUMER_DOMAINS = List.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "hotmail.com", "outlook.com", "live.com",
            "aol.com", "icloud.com", "me.com", "proton.me", "protonmail.com", "gmx.com", "mail.com", "yandex.com",
            "rediffmail.com", "zoho.com");

    /**
     * Sentinel for an absent search term. Chosen so it cannot collide with real
     * data: no email address, digit string or surname contains tildes. Passing a
     * sentinel rather than a NULL keeps every bind parameter unambiguously typed,
     * which matters because PostgreSQL cannot infer the type of a bare
     * {@code ? is null}.
     */
    private static final String NONE = "~~none~~";

    private static final UUID NO_EXCLUSION = new UUID(0L, 0L);

    private final JdbcTemplate jdbc;

    public LeadMatchingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A possible existing record for an inbound lead. */
    public record Candidate(String candidateType, UUID candidateId, String label, BigDecimal confidence,
                            String basis, UUID accountId, UUID ownerId) {}

    /** The account an inbound lead most likely belongs to, awaiting confirmation. */
    public record AccountMatch(UUID accountId, String accountName, BigDecimal confidence, String basis) {}

    public record MatchInput(String firstName, String lastName, String company, String email, String phone) {

        String emailDomain() {
            if (email == null || !email.contains("@")) return null;
            return email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
        }

        String phoneDigits() {
            return phone == null ? "" : phone.replaceAll("\\D", "");
        }
    }

    public static boolean isConsumerDomain(String domain) {
        return domain != null && CONSUMER_DOMAINS.contains(domain.toLowerCase(Locale.ROOT));
    }

    /**
     * Finds candidate duplicates, strongest confidence first.
     *
     * @param excludeLeadId a lead to leave out of the search — the lead being
     *                      re-checked is not its own duplicate
     */
    @Transactional(readOnly = true)
    public List<Candidate> findDuplicates(MatchInput input, LeadConfigService.DuplicatePolicyRow policy,
                                          UUID excludeLeadId) {
        UUID tenantId = TenantContext.get().tenantId();
        String email = value(input.email());
        String digits = input.phoneDigits().length() >= 6 ? input.phoneDigits() : NONE;
        String surname = value(input.lastName());
        double threshold = policy.nameSimilarityThreshold().doubleValue();
        List<Candidate> candidates = new ArrayList<>();

        // --- existing leads -------------------------------------------------
        jdbc.query("""
                select l.id, l.first_name, l.last_name, l.company, l.email, l.phone, l.owner_id
                from crm.lead l
                where l.tenant_id = ? and l.deleted_at is null and l.id <> ?
                  and (lower(coalesce(l.email, '')) = ?
                       or regexp_replace(coalesce(l.phone, ''), '\\D', '', 'g') = ?
                       or lower(l.last_name) = ?)
                limit 200
                """, rs -> {
            String candidateEmail = rs.getString("email");
            String candidatePhone = rs.getString("phone");
            String candidateCompany = rs.getString("company");
            String label = rs.getString("first_name") + " " + rs.getString("last_name")
                    + " (" + candidateCompany + ")";
            UUID id = rs.getObject("id", UUID.class);
            UUID ownerId = rs.getObject("owner_id", UUID.class);
            if (policy.matchEmail() && !NONE.equals(email) && email.equalsIgnoreCase(candidateEmail)) {
                candidates.add(new Candidate("LEAD", id, label, bd(0.97),
                        "Same email address as an existing lead", null, ownerId));
                return;
            }
            if (policy.matchPhone() && !NONE.equals(digits)
                    && digits.equals(candidatePhone == null ? "" : candidatePhone.replaceAll("\\D", ""))) {
                candidates.add(new Candidate("LEAD", id, label, bd(0.82),
                        "Same telephone number as an existing lead", null, ownerId));
                return;
            }
            double similarity = NameSimilarity.score(input.company(), candidateCompany);
            if (similarity >= threshold) {
                candidates.add(new Candidate("LEAD", id, label, bd(similarity * 0.9d),
                        "Same surname at a similarly named company", null, ownerId));
            }
        }, tenantId, excludeLeadId == null ? NO_EXCLUSION : excludeLeadId, email, digits, surname);

        // --- existing contacts ----------------------------------------------
        jdbc.query("""
                select c.id, c.first_name, c.last_name, c.email, c.account_id,
                       a.name as account_name, a.owner_id
                from crm.contact c
                left join crm.account a on a.tenant_id = c.tenant_id and a.id = c.account_id
                where c.tenant_id = ?
                  and (lower(coalesce(c.email, '')) = ? or lower(c.last_name) = ?)
                limit 200
                """, rs -> {
            String candidateEmail = rs.getString("email");
            String accountName = rs.getString("account_name");
            String label = rs.getString("first_name") + " " + rs.getString("last_name")
                    + (accountName == null ? "" : " at " + accountName);
            UUID id = rs.getObject("id", UUID.class);
            UUID accountId = rs.getObject("account_id", UUID.class);
            UUID ownerId = rs.getObject("owner_id", UUID.class);
            if (policy.matchEmail() && !NONE.equals(email) && email.equalsIgnoreCase(candidateEmail)) {
                candidates.add(new Candidate("CONTACT", id, label, bd(0.95),
                        "Same email address as an existing contact", accountId, ownerId));
                return;
            }
            double similarity = NameSimilarity.score(input.company(), accountName);
            if (accountName != null && similarity >= threshold) {
                candidates.add(new Candidate("CONTACT", id, label, bd(similarity * 0.85d),
                        "Same surname at a similarly named account", accountId, ownerId));
            }
        }, tenantId, email, surname);

        // --- existing accounts ----------------------------------------------
        for (AccountMatch match : accountCandidates(input, policy)) {
            candidates.add(new Candidate("ACCOUNT", match.accountId(), match.accountName(), match.confidence(),
                    match.basis(), match.accountId(), null));
        }

        candidates.sort(Comparator.comparing(Candidate::confidence).reversed());
        List<Candidate> unique = new ArrayList<>();
        for (Candidate candidate : candidates) {
            boolean seen = unique.stream().anyMatch(existing ->
                    existing.candidateType().equals(candidate.candidateType())
                            && existing.candidateId().equals(candidate.candidateId()));
            if (!seen) unique.add(candidate);
        }
        return unique;
    }

    /**
     * Matches an inbound lead to an account by domain first, name similarity
     * second (FR-LED-005). Domain is the stronger signal and is tried first, but
     * only for corporate domains — matching on gmail.com would attach every
     * consumer address to whichever account happened to have one.
     */
    @Transactional(readOnly = true)
    public List<AccountMatch> accountCandidates(MatchInput input, LeadConfigService.DuplicatePolicyRow policy) {
        UUID tenantId = TenantContext.get().tenantId();
        String domain = input.emailDomain();
        double threshold = policy.nameSimilarityThreshold().doubleValue();
        List<AccountMatch> matches = new ArrayList<>();

        if (policy.matchCompanyDomain() && domain != null && !isConsumerDomain(domain)) {
            // crm.account carries no website column (E04's schema), so the
            // authoritative domain evidence available is the email domain of
            // people already recorded at that account.
            matches.addAll(jdbc.query("""
                    select a.id, a.name, count(*) as contact_count
                    from crm.account a
                    join crm.contact c on c.tenant_id = a.tenant_id and c.account_id = a.id
                    where a.tenant_id = ? and a.deleted_at is null
                      and lower(split_part(coalesce(c.email, ''), '@', 2)) = ?
                    group by a.id, a.name
                    order by count(*) desc
                    limit 5
                    """, (rs, i) -> new AccountMatch(rs.getObject("id", UUID.class), rs.getString("name"),
                    bd(0.93), "Email domain " + domain + " already appears on "
                    + rs.getLong("contact_count") + " contact(s) at this account"), tenantId, domain));
        }

        if (input.company() != null && !input.company().isBlank()) {
            List<Object[]> accounts = jdbc.query("""
                    select id, name from crm.account
                    where tenant_id = ? and deleted_at is null
                    limit 2000
                    """, (rs, i) -> new Object[]{rs.getObject("id", UUID.class), rs.getString("name")}, tenantId);
            for (Object[] account : accounts) {
                double similarity = NameSimilarity.score(input.company(), (String) account[1]);
                if (similarity >= threshold) {
                    UUID id = (UUID) account[0];
                    if (matches.stream().noneMatch(m -> m.accountId().equals(id))) {
                        matches.add(new AccountMatch(id, (String) account[1], bd(similarity),
                                "Company name is " + Math.round(similarity * 100) + "% similar to this account"));
                    }
                }
            }
        }

        matches.sort(Comparator.comparing(AccountMatch::confidence).reversed());
        return matches.size() > 5 ? List.copyOf(matches.subList(0, 5)) : matches;
    }

    private static String value(String raw) {
        return raw == null || raw.isBlank() ? NONE : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }
}
