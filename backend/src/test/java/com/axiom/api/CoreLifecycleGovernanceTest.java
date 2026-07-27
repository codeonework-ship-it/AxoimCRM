package com.axiom.api;

import com.axiom.accounts.AccountService;
import com.axiom.accounts.ActorSession;
import com.axiom.accounts.ContactService;
import com.axiom.accounts.DuplicateService;
import com.axiom.audit.AuditService;
import com.axiom.domain.AccountRepository;
import com.axiom.domain.ContactRepository;
import com.axiom.domain.LeadRepository;
import com.axiom.domain.OpportunityRepository;
import com.axiom.domain.PipelineStageRepository;
import com.axiom.notifications.NotificationWriter;
import com.axiom.outbox.OutboxWriter;
import com.axiom.pipeline.PipelineQueries;
import com.axiom.security.AuthorizationService;
import com.axiom.security.SecurableObject;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0 contract: every core lifecycle mutation is authorized, audited and emitted atomically. */
class CoreLifecycleGovernanceTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach void bindTenant() {
        TenantContext.set(new TenantContext.Principal(tenantId, userId, "architect@axiom.test",
                "Architect", "TENANT_ADMIN"));
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void accountLifecycleRequiresRecordDeleteAndWritesAuditAndOutbox() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditService audit = mock(AuditService.class);
        AuthorizationService authorization = mock(AuthorizationService.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        AccountService service = spy(new AccountService(jdbc, audit, mock(DuplicateService.class),
                mock(ActorSession.class), authorization, outbox));
        UUID id = UUID.randomUUID();
        AccountService.AccountDetail before = mock(AccountService.AccountDetail.class);
        when(before.name()).thenReturn("Meridian");
        when(before.status()).thenReturn("ACTIVE");
        doReturn(before).when(service).get(id);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.changeLifecycle(id, new AccountService.LifecycleRequest(false, 7, "Payment overdue"));

        verify(authorization).requireDelete(SecurableObject.ACCOUNT, id);
        verify(audit).recordWithReason(eq("ACCOUNT_INACTIVE"), eq("ACCOUNT"), eq(id),
                anyString(), eq("Payment overdue"), anyMap());
        verify(outbox).write(eq("account"), eq(id), eq("account.inactivated"), anyMap());
        assertTransactional(AccountService.class, "changeLifecycle", UUID.class, AccountService.LifecycleRequest.class);
    }

    @Test void leadUpdateRequiresRecordEditAndWritesAuditAndOutbox() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditService audit = mock(AuditService.class);
        AuthorizationService authorization = mock(AuthorizationService.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        LeadService service = spy(new LeadService(mock(LeadRepository.class), mock(AccountRepository.class),
                mock(ContactRepository.class), mock(OpportunityRepository.class), mock(PipelineStageRepository.class),
                outbox, mock(NotificationWriter.class), jdbc, audit, authorization));
        UUID id = UUID.randomUUID();
        LeadService.LeadDetail before = new LeadService.LeadDetail(id, "Maya", "Torres", "Meridian",
                "maya@example.test", null, null, "NEW", userId, "Architect", 50, null,
                "API", null, null, null, null, Instant.now(), Instant.now(), 3);
        doReturn(before).when(service).get(id);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.update(id, new LeadService.LeadUpdateRequest("Maya", "Torres", "Meridian II",
                "maya@example.test", null, null, null, "API", null, null, null, null, 3));

        verify(authorization).requireEdit(SecurableObject.LEAD, id);
        verify(audit).record(eq("LEAD_UPDATE"), eq("LEAD"), eq(id), anyString(), anyMap());
        verify(outbox).write(eq("lead"), eq(id), eq("lead.updated"), anyMap());
        assertTransactional(LeadService.class, "update", UUID.class, LeadService.LeadUpdateRequest.class);
    }

    @Test void contactUpdateRequiresRecordEditAndWritesAuditAndOutbox() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditService audit = mock(AuditService.class);
        AuthorizationService authorization = mock(AuthorizationService.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        UUID id = UUID.randomUUID();
        ContactService.ContactDetail before = mock(ContactService.ContactDetail.class);
        when(before.version()).thenReturn(4L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        DuplicateService duplicate = mock(DuplicateService.class);
        ContactService governed = spy(new ContactService(jdbc, audit, duplicate,
                mock(ActorSession.class), authorization, outbox));
        doReturn(before).when(governed).get(id);
        when(duplicate.assess(any())).thenReturn(DuplicateService.Assessment.clear(java.util.List.of()));
        ContactService.ContactRequest request = new ContactService.ContactRequest(
                "Maya", "Torres", null, null, null, null, null, userId,
                "maya@example.test", null, null, "ACTIVE", "API", null, false, null);

        governed.update(id, 4, request);

        verify(authorization).requireEdit(SecurableObject.CONTACT, id);
        verify(audit).record(eq("CONTACT_UPDATE"), eq("CONTACT"), eq(id), anyString(), anyMap());
        verify(outbox).write(eq("contact"), eq(id), eq("contact.updated"), anyMap());
        assertTransactional(ContactService.class, "update", UUID.class, long.class,
                ContactService.ContactRequest.class);
    }

    @Test void opportunityCreateChecksObjectAndParentAccessThenWritesAuditAndOutbox() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PipelineQueries pipelines = mock(PipelineQueries.class);
        AuthorizationService authorization = mock(AuthorizationService.class);
        AuditService audit = mock(AuditService.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        OpportunityCrudService service = spy(new OpportunityCrudService(jdbc, pipelines, authorization, audit, outbox));
        UUID accountId = UUID.randomUUID();
        UUID pipelineId = UUID.randomUUID();
        UUID stageId = UUID.randomUUID();
        UUID opportunityId = UUID.randomUUID();
        when(pipelines.defaultPipelineId()).thenReturn(pipelineId);
        when(pipelines.firstOpenStage(pipelineId)).thenReturn(stage(stageId, pipelineId));
        when(pipelines.stage(stageId)).thenReturn(stage(stageId, pipelineId));
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(opportunityId);
        OpportunityCrudService.OpportunityDetail created = mock(OpportunityCrudService.OpportunityDetail.class);
        doReturn(created).when(service).get(opportunityId);

        service.create(new OpportunityCrudService.OpportunityRequest("Expansion", accountId, null, null,
                new BigDecimal("125000"), null, null, "USD", null, null, "Confirm scope", null));

        verify(authorization).requireCreate(SecurableObject.OPPORTUNITY);
        verify(authorization).requireRead(SecurableObject.ACCOUNT, accountId);
        verify(audit).record(eq("OPPORTUNITY_CREATE"), eq("OPPORTUNITY"), eq(opportunityId), anyString(), anyMap());
        verify(outbox).write(eq("opportunity"), eq(opportunityId), eq("opportunity.created"), anyMap());
        assertTransactional(OpportunityCrudService.class, "create", OpportunityCrudService.OpportunityRequest.class);
    }

    private static PipelineQueries.StageRow stage(UUID id, UUID pipelineId) {
        return new PipelineQueries.StageRow(id, pipelineId, "Direct", "Qualification", 10, 1,
                false, false, false, true, false, new BigDecimal("10"), "PIPELINE", 14);
    }

    private static void assertTransactional(Class<?> type, String method, Class<?>... arguments) throws Exception {
        assertNotNull(type.getMethod(method, arguments).getAnnotation(Transactional.class),
                "Mutation and audit/outbox writes must share one transaction");
    }
}
