package com.axiom.accounts;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure matching arithmetic behind FR-ACC-008. Kept free of JDBC and of tenant
 * context so the confidence a steward is shown can be asserted directly in a
 * unit test rather than inferred from a query plan.
 *
 * <p>Confidence is a 0..1 number, not a label. The rule row decides what a given
 * confidence means (blocking or warning, and above which threshold); the matcher
 * only measures similarity.
 */
public final class DuplicateMatcher {

    /** Legal-form noise that makes two spellings of one company look different. */
    private static final Set<String> LEGAL_SUFFIXES = Set.of(
            "ltd", "limited", "inc", "incorporated", "llc", "llp", "lp", "plc", "pvt",
            "private", "co", "company", "corp", "corporation", "gmbh", "ag", "sa", "sas",
            "bv", "nv", "oy", "ab", "as", "pte", "sdn", "bhd", "group", "holdings", "the");

    private DuplicateMatcher() {}

    // ------------------------------------------------------------ normalization

    /** Lower-cased, punctuation-free, legal-suffix-free comparison key. */
    public static String normalizeName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (cleaned.isEmpty()) return "";
        List<String> kept = Arrays.stream(cleaned.split(" "))
                .filter(token -> !token.isBlank())
                .filter(token -> !LEGAL_SUFFIXES.contains(token))
                .filter(token -> !token.equals("and"))
                .toList();
        // A name that is nothing but legal forms keeps its original tokens rather
        // than normalizing to the empty string and matching everything.
        return kept.isEmpty() ? cleaned : String.join(" ", kept);
    }

    public static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Digits only, last ten kept, so country and trunk prefixes stop mattering. */
    public static String normalizePhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
    }

    /** Host of a website or the domain part of an email address, without {@code www.}. */
    public static String normalizeDomain(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        value = value.replaceFirst("^[a-z]+://", "");
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        if (value.startsWith("www.")) value = value.substring(4);
        return value;
    }

    // ------------------------------------------------------------------ scoring

    /**
     * Similarity of two names in 0..1: the better of a character-level
     * Jaro-Winkler score and a whole-token overlap score. The token score is
     * what catches re-orderings ("Argyle &amp; Fenmore" vs "Fenmore &amp; Argyle");
     * Jaro-Winkler is what catches typos.
     */
    public static double nameConfidence(String left, String right) {
        String a = normalizeName(left);
        String b = normalizeName(right);
        if (a.isEmpty() || b.isEmpty()) return 0d;
        if (a.equals(b)) return 1d;
        return Math.max(jaroWinkler(a, b), tokenOverlap(a, b));
    }

    public static double exactConfidence(String left, String right) {
        return !left.isEmpty() && left.equals(right) ? 1d : 0d;
    }

    static double tokenOverlap(String a, String b) {
        Set<String> left = new LinkedHashSet<>(Arrays.asList(a.split(" ")));
        Set<String> right = new LinkedHashSet<>(Arrays.asList(b.split(" ")));
        if (left.isEmpty() || right.isEmpty()) return 0d;
        long shared = left.stream().filter(right::contains).count();
        // Dice coefficient: rewards a shared core without punishing one side for
        // carrying an extra descriptive word.
        return (2d * shared) / (left.size() + right.size());
    }

    static double jaroWinkler(String a, String b) {
        double jaro = jaro(a, b);
        if (jaro < 0.7d) return jaro;
        int prefix = 0;
        int max = Math.min(4, Math.min(a.length(), b.length()));
        while (prefix < max && a.charAt(prefix) == b.charAt(prefix)) prefix++;
        return jaro + (prefix * 0.1d * (1d - jaro));
    }

    static double jaro(String a, String b) {
        if (a.equals(b)) return 1d;
        int lenA = a.length();
        int lenB = b.length();
        if (lenA == 0 || lenB == 0) return 0d;
        int window = Math.max(lenA, lenB) / 2 - 1;
        if (window < 0) window = 0;
        boolean[] usedA = new boolean[lenA];
        boolean[] usedB = new boolean[lenB];
        int matches = 0;
        for (int i = 0; i < lenA; i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(lenB, i + window + 1);
            for (int j = from; j < to; j++) {
                if (usedB[j] || a.charAt(i) != b.charAt(j)) continue;
                usedA[i] = true;
                usedB[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0d;
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < lenA; i++) {
            if (!usedA[i]) continue;
            while (!usedB[k]) k++;
            if (a.charAt(i) != b.charAt(k)) transpositions++;
            k++;
        }
        double half = transpositions / 2d;
        return ((double) matches / lenA + (double) matches / lenB
                + (matches - half) / matches) / 3d;
    }

    /** Rounds to three decimals so a confidence shown to a user is stable. */
    public static double round(double value) {
        return Math.round(value * 1000d) / 1000d;
    }
}
