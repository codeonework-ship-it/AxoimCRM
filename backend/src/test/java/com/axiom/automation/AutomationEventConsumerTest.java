package com.axiom.automation;

import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-003: delivery is at-least-once, so a duplicate delivery must produce
 * exactly one effect.
 */
class AutomationEventConsumerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RECORD = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private ObjectMetadataService metadata;
    private RecordChangeDispatcher dispatcher;
    private AutomationEventConsumer consumer;

    private static final ObjectMetadataService.ObjectDescriptor OPPORTUNITY =
            new ObjectMetadataService.ObjectDescriptor(UUID.randomUUID(), "OPPORTUNITY", "Opportunity",
                    "sales", "opportunity", "id", "owner_id", null, List.of("id"), null, null,
                    Map.of("id", "uuid", "amount", "numeric"));

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        metadata = mock(ObjectMetadataService.class);
        dispatcher = mock(RecordChangeDispatcher.class);
        when(metadata.list()).thenReturn(List.of(OPPORTUNITY));
        when(metadata.readRecord(eq("OPPORTUNITY"), any()))
                .thenReturn(Map.of("id", RECORD, "amount", java.math.BigDecimal.TEN));
        when(dispatcher.dispatch(anyString(), any(), anyString(), any(), any(), anyInt()))
                .thenReturn(new RecordChangeDispatcher.DispatchResult("OPPORTUNITY", RECORD,
                        "UPDATE", 1, false, null, List.of()));
        consumer = new AutomationEventConsumer(jdbc, new ObjectMapper(), metadata, dispatcher);
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "TENANT_ADMIN",
                "Admin", "admin@example.com"));
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    @Test void aDuplicateDeliveryProducesExactlyOneEffect() {
        // First delivery inserts the receipt; the second conflicts and inserts nothing.
        when(jdbc.update(contains("insert into automation.event_receipt"), any(Object[].class)))
                .thenReturn(1).thenReturn(0);

        AutomationEventConsumer.HandleResult first = consumer.handle("evt-1", "OPPORTUNITY", RECORD,
                "sales.opportunity.updated", Map.of());
        AutomationEventConsumer.HandleResult second = consumer.handle("evt-1", "OPPORTUNITY", RECORD,
                "sales.opportunity.updated", Map.of());

        assertEquals("PROCESSED", first.outcome());
        assertEquals("DUPLICATE", second.outcome());
        assertTrue(second.detail().contains("already processed"));

        // The whole point: the record was dispatched once, not twice.
        verify(dispatcher, times(1))
                .dispatch(eq("OPPORTUNITY"), eq(RECORD), eq("UPDATE"), any(), any(), eq(0));
    }

    @Test void anEventForAnUnregisteredAggregateIsIgnoredNotFailed() {
        when(jdbc.update(contains("insert into automation.event_receipt"), any(Object[].class)))
                .thenReturn(1);
        AutomationEventConsumer.HandleResult result = consumer.handle("evt-2", "QUOTE", RECORD,
                "cpq.quote.updated", Map.of());
        assertEquals("IGNORED", result.outcome());
        verify(dispatcher, times(0)).dispatch(anyString(), any(), anyString(), any(), any(), anyInt());
    }

    @Test void thePayloadsBeforeMapBecomesTheOldValuesTheEntryConditionSees() {
        when(jdbc.update(contains("insert into automation.event_receipt"), any(Object[].class)))
                .thenReturn(1);
        consumer.handle("evt-3", "OPPORTUNITY", RECORD, "sales.opportunity.updated",
                Map.of("before", Map.of("amount", 100000)));

        verify(dispatcher).dispatch(eq("OPPORTUNITY"), eq(RECORD), eq("UPDATE"),
                eq(Map.of("amount", 100000)), any(), eq(0));
    }

    @Test void theEventTypeIsMappedOntoTheFourRecordEvents() {
        assertEquals("CREATE", AutomationEventConsumer.eventFor("crm.lead.created"));
        assertEquals("UPDATE", AutomationEventConsumer.eventFor("crm.lead.updated"));
        assertEquals("DELETE", AutomationEventConsumer.eventFor("crm.lead.deleted"));
        assertEquals("UNDELETE", AutomationEventConsumer.eventFor("crm.lead.undeleted"));
        assertEquals("UNDELETE", AutomationEventConsumer.eventFor("crm.lead.restored"));
        assertEquals("UPDATE", AutomationEventConsumer.eventFor("something.else"));
    }
}
