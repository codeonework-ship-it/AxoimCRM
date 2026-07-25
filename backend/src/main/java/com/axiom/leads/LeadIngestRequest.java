package com.axiom.leads;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

/**
 * One inbound lead, from the API, a bulk batch or a web form (FR-LED-002,
 * FR-LED-003).
 *
 * <p>The jakarta constraints here are enforced for a <b>single</b> submission,
 * where a 400 naming the bad field is the right answer. They are deliberately
 * <b>not</b> cascaded from the bulk request: a batch must return per-record
 * results rather than be rejected wholesale, so
 * {@link LeadValidation#problems(LeadIngestRequest)} re-states the same rules in a
 * form that yields a list of messages per row instead of an exception for the
 * batch.
 */
public record LeadIngestRequest(
        @NotBlank(message = "a first name is required")
        @Size(max = 120, message = "must be 120 characters or fewer")
        String firstName,

        @NotBlank(message = "a last name is required")
        @Size(max = 120, message = "must be 120 characters or fewer")
        String lastName,

        @NotBlank(message = "a company is required")
        @Size(max = 240, message = "must be 240 characters or fewer")
        String company,

        @Email(message = "must look like an email address, for example name@company.com")
        @Size(max = 240, message = "must be 240 characters or fewer")
        String email,

        @Size(max = 60, message = "must be 60 characters or fewer")
        String phone,

        String title,
        String source,
        String campaignCode,
        String territory,
        String segment,
        String productInterest,
        String rating,
        String status,
        UUID ownerId,
        String notes,
        Map<String, Object> qualificationData,
        Map<String, Object> customFields) {

    public LeadIngestRequest {
        qualificationData = qualificationData == null ? Map.of() : Map.copyOf(qualificationData);
        customFields = customFields == null ? Map.of() : Map.copyOf(customFields);
    }

    public LeadMatchingService.MatchInput matchInput() {
        return new LeadMatchingService.MatchInput(firstName, lastName, company, email, phone);
    }

    public LeadSnapshot snapshot(long activityCount) {
        return LeadSnapshot.builder()
                .put("firstName", firstName)
                .put("lastName", lastName)
                .put("company", company)
                .email(email)
                .put("phone", phone)
                .put("title", title)
                .put("source", source)
                .put("campaignCode", campaignCode)
                .put("territory", territory)
                .put("segment", segment)
                .put("productInterest", productInterest)
                .put("rating", rating)
                .put("status", status)
                .putNumber("activityCount", activityCount)
                .qualification(qualificationData)
                .custom(customFields)
                .build();
    }
}
