package com.axiom.leads;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Company-name similarity for lead-to-account matching (FR-LED-005).
 *
 * <p>Straight string distance is the wrong tool for company names: "Meridian
 * Fabrication Group Pvt Ltd" and "Meridian Fabrication" are the same customer
 * and are 40% different by edit distance, while "Northstar Logistics" and
 * "Northstar Logistic" are 5% different and also the same customer. So the
 * comparison is done twice — token overlap, which handles missing or extra
 * words, and edit distance, which handles typos — and the stronger signal wins.
 *
 * <p>Legal-form suffixes are stripped first. They are the noisiest tokens in any
 * CRM: they are inconsistently entered, carry no identifying information, and
 * left in place they inflate the similarity of every pair of limited companies.
 */
public final class NameSimilarity {

    private static final Set<String> LEGAL_FORMS = Set.of(
            "inc", "inc.", "incorporated", "ltd", "ltd.", "limited", "llc", "llp", "lp", "plc", "pvt", "private",
            "pte", "corp", "corp.", "corporation", "co", "co.", "company", "gmbh", "ag", "sa", "bv", "nv", "oy",
            "ab", "as", "srl", "spa", "kk", "group", "holdings", "holding", "international", "global", "the");

    private NameSimilarity() {}

    /** @return 0.0 (nothing in common) to 1.0 (identical after normalisation) */
    public static double score(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) return 0.0d;
        if (a.equals(b)) return 1.0d;

        Set<String> aTokens = tokens(a);
        Set<String> bTokens = tokens(b);
        double tokenScore = 0.0d;
        if (!aTokens.isEmpty() && !bTokens.isEmpty()) {
            Set<String> shared = new LinkedHashSet<>(aTokens);
            shared.retainAll(bTokens);
            // Overlap against the SHORTER side, not the union: an abbreviated
            // entry is a subset of the full name, and Jaccard would punish that
            // exactly when we most want a match.
            tokenScore = (double) shared.size() / Math.min(aTokens.size(), bTokens.size());
        }
        double editScore = 1.0d - ((double) levenshtein(a, b) / Math.max(a.length(), b.length()));
        return Math.max(0.0d, Math.min(1.0d, Math.max(tokenScore, editScore)));
    }

    /** Lower case, accents folded, punctuation dropped, legal forms removed. */
    public static String normalize(String value) {
        if (value == null) return "";
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<String> kept = Arrays.stream(folded.split(" "))
                .filter(token -> !token.isEmpty() && !LEGAL_FORMS.contains(token))
                .toList();
        return kept.isEmpty() ? folded : String.join(" ", kept);
    }

    private static Set<String> tokens(String normalized) {
        return new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
