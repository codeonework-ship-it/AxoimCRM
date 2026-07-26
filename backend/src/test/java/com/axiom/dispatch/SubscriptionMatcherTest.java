package com.axiom.dispatch;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionMatcherTest {

    @Test void matchesExactPrefixAndWildcardEventTypes() {
        assertTrue(SubscriptionMatcher.matchesType("*", "lead.converted"));
        assertTrue(SubscriptionMatcher.matchesType("lead.*", "lead.converted"));
        assertTrue(SubscriptionMatcher.matchesType("LEAD.CONVERTED", "lead.converted"));
        assertFalse(SubscriptionMatcher.matchesType("lead.*", "opportunity.closed"));
        assertFalse(SubscriptionMatcher.matchesType("lead.converted", "lead.converted.v2"));
        assertFalse(SubscriptionMatcher.matchesType("", "lead.converted"));
    }

    @Test void filtersOnPayloadValuesIncludingNestedPaths() {
        Map<String, Object> payload = Map.of("stage", "CLOSED_WON", "detail", Map.of("region", "EMEA"));

        assertTrue(SubscriptionMatcher.matchesFilter(null, payload), "no filter means everything");
        assertTrue(SubscriptionMatcher.matchesFilter("stage=CLOSED_WON", payload));
        assertTrue(SubscriptionMatcher.matchesFilter("stage=closed_won && detail.region=EMEA", payload));
        assertFalse(SubscriptionMatcher.matchesFilter("stage=CLOSED_LOST", payload));
        assertTrue(SubscriptionMatcher.matchesFilter("stage!=CLOSED_LOST", payload));
        assertFalse(SubscriptionMatcher.matchesFilter("missing=anything", payload));
    }

    @Test void anUnparseableFilterMatchesNothingRatherThanEverything() {
        assertFalse(SubscriptionMatcher.matchesFilter("this is not a filter", Map.of("a", "b")),
                "a broken filter must fail closed, or an endpoint gets a firehose it never asked for");
    }
}
