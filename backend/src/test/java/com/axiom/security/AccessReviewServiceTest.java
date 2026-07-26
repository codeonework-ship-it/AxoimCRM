package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AccessReviewServiceTest {
    private AccessReviewService service;

    @BeforeEach void setUp() {
        service = new AccessReviewService(mock(JdbcTemplate.class), mock(AuditService.class));
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SUPER_AUDIT", "Read-only auditor", "audit@example.test"));
    }

    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test void auditorCannotCreateAnAccessReview() {
        assertThrows(ForbiddenException.class, () -> service.create(new AccessReviewService.CreateRequest(
                "Q3_ACCESS", "Quarterly review", "All live grants", Instant.now().plusSeconds(86400))));
    }

    @Test void auditorCannotCertifyOrRevokeAccess() {
        assertThrows(ForbiddenException.class, () -> service.decide(UUID.randomUUID(),
                new AccessReviewService.DecisionRequest("CONFIRMED", "Still required")));
    }
}
