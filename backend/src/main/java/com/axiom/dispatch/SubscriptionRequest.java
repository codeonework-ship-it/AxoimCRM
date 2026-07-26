package com.axiom.dispatch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Outbound event subscription — what turns a domain event into a dispatch. */
public record SubscriptionRequest(
        @NotBlank
        @Size(max = 200)
        String eventTypePattern,

        @Size(max = 500)
        String filterExpression,

        Boolean active) {
}
