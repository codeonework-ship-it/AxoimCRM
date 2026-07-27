package com.axiom.sandbox;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.outbox.OutboxWriter;
import com.axiom.security.MakerCheckerService;
import com.axiom.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReleaseManagementServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private JdbcTemplate jdbc;
    private MakerCheckerService approvals;
    private AuditService audit;
    private OutboxWriter outbox;
    private ReleaseManagementService releases;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        approvals = mock(MakerCheckerService.class);
        audit = mock(AuditService.class);
        outbox = mock(OutboxWriter.class);
        releases = new ReleaseManagementService(jdbc, approvals, audit, outbox, new ObjectMapper());
        bind("SUPER_ADMIN");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void outboundTrafficNeedsTheExactRiskAcknowledgementBeforeAnyWrite() {
        var request = new ReleaseManagementService.OutboundRequest(true, false, false,
                "I understand test data may contact real recipients");

        assertThatThrownBy(() -> releases.configureOutbound(UUID.randomUUID(), request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("exact acknowledgement");

        verifyNoInteractions(jdbc, approvals, audit, outbox);
    }

    @Test
    void impossibleRecoveryWindowIsRejectedBeforeReadingOrWritingEvidence() {
        Instant started = Instant.parse("2026-07-27T10:00:00Z");
        var request = new ReleaseManagementService.DrValidationRequest(
                "SINGLE_AZ", "restored-e19", "backup-20260727",
                "a".repeat(64), started, started.minusSeconds(1), started, started,
                Map.of("accounts", 1L));

        assertThatThrownBy(() -> releases.validateRecovery(request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("completion cannot be before recovery start");

        verifyNoInteractions(jdbc, approvals, audit, outbox);
    }

    @Test
    void ordinaryCrmRoleCannotReadReleaseOrRecoveryEvidence() {
        bind("SALES");

        assertThatThrownBy(releases::sandboxes)
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(releases::packages)
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(releases::recoveryBaseline)
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(jdbc, approvals, audit, outbox);
    }

    @Test
    void readOnlyAuditorCannotCreateSandbox() {
        bind("SUPER_AUDIT");
        var request = new ReleaseManagementService.SandboxRequest(
                "AUDIT_BOX", "Audit sandbox", "DEV", "CONFIGURATION_ONLY");

        assertThatThrownBy(() -> releases.createSandbox(request))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(jdbc, approvals, audit, outbox);
    }

    private void bind(String role) {
        TenantContext.set(new TenantContext.Principal(TENANT, ACTOR, role,
                "E19 Test Operator", "e19.operator@axiom.test"));
    }
}
