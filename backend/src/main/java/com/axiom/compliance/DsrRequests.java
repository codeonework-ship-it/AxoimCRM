package com.axiom.compliance;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;
import java.util.UUID;

/** Request payloads for the data-subject-request endpoints (FR-AUD-008). */
public final class DsrRequests {

    private DsrRequests() {}

    public record Raise(
            @NotBlank @Pattern(regexp = "ACCESS|RECTIFICATION|PORTABILITY|ERASURE",
                    message = "must be ACCESS, RECTIFICATION, PORTABILITY or ERASURE") String requestType,
            @NotBlank @Pattern(regexp = "CONTACT|LEAD|APP_USER",
                    message = "must be CONTACT, LEAD or APP_USER") String subjectType,
            UUID subjectId,
            @NotBlank @Email String subjectEmail,
            String subjectName,
            String notes) {}

    /** Field corrections for a rectification request: field name to new value. */
    public record Rectify(@NotNull Map<String, String> corrections) {}

    public record PolicyUpdate(
            @NotNull @Min(1) @Max(180) Integer accessWindowDays,
            @NotNull @Min(1) @Max(180) Integer rectificationWindowDays,
            @NotNull @Min(1) @Max(180) Integer portabilityWindowDays,
            @NotNull @Min(1) @Max(180) Integer erasureWindowDays,
            @Email String contactEmail) {}
}
