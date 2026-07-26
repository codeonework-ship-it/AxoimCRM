package com.axiom.leads;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Per-row validation shared by single and partial-success bulk ingestion. */
public final class LeadValidation {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private LeadValidation() {}

    public static List<String> problems(LeadIngestRequest request) {
        List<String> problems = new ArrayList<>();
        if (request == null) return List.of("The lead row is empty.");
        required(request.firstName(), 120, "First name", problems);
        required(request.lastName(), 120, "Last name", problems);
        required(request.company(), 240, "Company", problems);
        optional(request.email(), 240, "Email", problems);
        optional(request.phone(), 60, "Phone", problems);
        if (request.email() != null && !request.email().isBlank()
                && !EMAIL.matcher(request.email().trim()).matches()) {
            problems.add("Email must look like name@company.com.");
        }
        return List.copyOf(problems);
    }

    private static void required(String value, int max, String label, List<String> problems) {
        if (value == null || value.isBlank()) problems.add(label + " is required.");
        else optional(value, max, label, problems);
    }

    private static void optional(String value, int max, String label, List<String> problems) {
        if (value != null && value.length() > max) problems.add(label + " must be " + max + " characters or fewer.");
    }
}
