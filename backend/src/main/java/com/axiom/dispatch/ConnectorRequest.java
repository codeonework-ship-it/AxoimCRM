package com.axiom.dispatch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Connector registry entry (FR-INT-008). */
public record ConnectorRequest(
        @NotBlank
        @Pattern(regexp = "WEBHOOK|ERP|ESIGN|MARKETING|ENRICHMENT|CTRM",
                 message = "Connector type must be one of WEBHOOK, ERP, ESIGN, MARKETING, ENRICHMENT, CTRM")
        String connectorType,

        @NotBlank @Size(max = 100)
        String vendor,

        @NotBlank @Size(max = 120)
        String displayName,

        Boolean enabled,

        /**
         * Adapter configuration in our vocabulary — endpoint url, timeout,
         * static headers. A secret placed in here is stripped on save and on
         * read: secrets belong in a named credential, and a config field that
         * silently accepted one would make {@code FR-INT-007} advisory.
         */
        Map<String, Object> config,

        @Size(max = 64)
        String credentialRef) {
}
