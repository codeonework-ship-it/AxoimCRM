package com.axiom.leads;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The flattened view of a lead that scoring rules (FR-LED-006) and predictive
 * factors (FR-LED-007) are evaluated against.
 *
 * <p>One flat map rather than a typed object on purpose: a rule is administrator
 * configured, so its {@code field_key} is data, and data cannot reference a Java
 * getter. Every key an administrator may name is therefore listed in one place —
 * {@link #keys()} — which is also what the rule builder offers in the UI, so the
 * two can never drift.
 *
 * <p>Derived keys exist where the raw column is not what a rule wants to talk
 * about: {@code emailDomain} because "is this a business address" is a domain
 * question, and {@code activityCount} because behaviour scoring needs a number,
 * not a join.
 */
public record LeadSnapshot(Map<String, String> attributes) {

    public LeadSnapshot {
        attributes = Map.copyOf(attributes);
    }

    /** Attribute keys an administrator may reference in a rule or factor. */
    public static Map<String, String> keys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("firstName", "First name");
        keys.put("lastName", "Last name");
        keys.put("company", "Company");
        keys.put("email", "Email address");
        keys.put("emailDomain", "Email domain");
        keys.put("phone", "Telephone");
        keys.put("title", "Job title");
        keys.put("source", "Lead source");
        keys.put("campaignCode", "Campaign");
        keys.put("territory", "Territory");
        keys.put("segment", "Segment");
        keys.put("productInterest", "Product interest");
        keys.put("rating", "Rating");
        keys.put("status", "Status");
        keys.put("activityCount", "Logged engagements");
        keys.put("qual:budget", "Qualification — budget");
        keys.put("qual:authority", "Qualification — authority");
        keys.put("qual:need", "Qualification — need");
        keys.put("qual:timeline", "Qualification — timeline");
        return keys;
    }

    public String value(String fieldKey) {
        return fieldKey == null ? null : attributes.get(fieldKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Everything a lead-shaped source (an ingest request or a stored row) offers. */
    public static final class Builder {
        private final Map<String, String> values = new HashMap<>();

        public Builder put(String key, String value) {
            if (key != null && value != null && !value.isBlank()) {
                values.put(key, value.trim());
            }
            return this;
        }

        public Builder putNumber(String key, Number value) {
            return value == null ? this : put(key, String.valueOf(value));
        }

        /** Adds {@code emailDomain} alongside the address itself. */
        public Builder email(String email) {
            put("email", email);
            if (email != null && email.contains("@")) {
                String domain = email.substring(email.lastIndexOf('@') + 1).trim().toLowerCase(Locale.ROOT);
                put("emailDomain", domain);
            }
            return this;
        }

        /** Qualification answers, exposed under the {@code qual:} namespace. */
        public Builder qualification(Map<String, ?> qualificationData) {
            if (qualificationData != null) {
                qualificationData.forEach((k, v) -> put("qual:" + k, v == null ? null : String.valueOf(v)));
            }
            return this;
        }

        /** Custom fields, exposed under the {@code custom:} namespace. */
        public Builder custom(Map<String, ?> customFields) {
            if (customFields != null) {
                customFields.forEach((k, v) -> put("custom:" + k, v == null ? null : String.valueOf(v)));
            }
            return this;
        }

        public LeadSnapshot build() {
            return new LeadSnapshot(values);
        }
    }
}
