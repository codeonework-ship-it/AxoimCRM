package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MakerCheckerServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final MakerCheckerService service = new MakerCheckerService(jdbc, audit, new ObjectMapper());

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void initiatorCannotApproveTheirOwnControlledActionAndAttemptIsAudited() {
        UUID tenantId = UUID.randomUUID();
        UUID maker = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        TenantContext.set(new TenantContext.Principal(tenantId, maker,
                "TENANT_ADMIN", "Maker", "maker@example.com"));
        MakerCheckerService.ApprovalRequest request = new MakerCheckerService.ApprovalRequest(
                requestId, "PERMISSION_GRANT", "APP_USER", UUID.randomUUID(), "Grant access", "{}",
                maker, "maker@example.com", Instant.now(), "PENDING", null, null, null, null);

        assertThrows(ForbiddenException.class,
                () -> service.assertApproverIsPermitted(request, maker));

        verify(audit).recordWithReason(eq("SEGREGATION_VIOLATION"), eq("APP_USER"),
                eq(request.entityId()), argThat(summary -> summary.contains("cannot approve")),
                eq("MAKER_CHECKER"), argThat(details -> requestId.toString().equals(details.get("approvalRequestId"))));
    }
}
