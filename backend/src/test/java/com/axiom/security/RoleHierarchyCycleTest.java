package com.axiom.security;

import com.axiom.audit.AuditService;
import com.axiom.common.ConflictException;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * FR-SEC-001's on-failure clause: "an attempted cycle is rejected naming the
 * conflicting roles."
 *
 * <p>Naming them is the requirement, not a courtesy. An administrator who is
 * told only "invalid hierarchy" has to reconstruct a tree by hand to find out
 * which two nodes collided.
 *
 * <p>The same rule is enforced twice — here at save time, and by the
 * {@code trg_role_node_reject_cycle} trigger in V13 for entry points that never
 * touch this service (bulk import, psql). Both are exercised: this test covers
 * the service, and the trigger is verified against a live database.
 */
class RoleHierarchyCycleTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private RoleHierarchyService service;

    private final UUID execId = UUID.randomUUID();
    private final UUID leadershipId = UUID.randomUUID();
    private final UUID westId = UUID.randomUUID();
    private final UUID opsId = UUID.randomUUID();

    private RoleHierarchyService.RoleNode exec;
    private RoleHierarchyService.RoleNode leadership;
    private RoleHierarchyService.RoleNode west;
    private RoleHierarchyService.RoleNode ops;
    private Map<UUID, RoleHierarchyService.RoleNode> byId;

    @BeforeEach void setUp() {
        service = new RoleHierarchyService(mock(JdbcTemplate.class), mock(AuthorizationService.class),
                mock(AuditService.class), mock(DelegatedAdminService.class), mock(AccessGrantLog.class));
        TenantContext.set(new TenantContext.Principal(TENANT, UUID.randomUUID(), "TENANT_ADMIN",
                "Raj Malhotra", "raj.malhotra@meridianfab.com"));

        exec = node(execId, "EXEC", null, "/EXEC/", 0);
        leadership = node(leadershipId, "SALES_LEADERSHIP", execId, "/EXEC/SALES_LEADERSHIP/", 1);
        west = node(westId, "APAC_WEST", leadershipId, "/EXEC/SALES_LEADERSHIP/APAC_WEST/", 2);
        ops = node(opsId, "OPERATIONS_TEAM", execId, "/EXEC/OPERATIONS_TEAM/", 1);

        byId = new LinkedHashMap<>();
        byId.put(execId, exec);
        byId.put(leadershipId, leadership);
        byId.put(westId, west);
        byId.put(opsId, ops);
    }

    @AfterEach void tearDown() {
        TenantContext.clear();
    }

    private RoleHierarchyService.RoleNode node(UUID id, String code, UUID parentId, String path, int depth) {
        return new RoleHierarchyService.RoleNode(id, code, code, null, parentId,
                null, path, depth, true, 0);
    }

    @Test void reparentingARoleUnderItsOwnDescendantIsRejectedNamingBothRoles() {
        // EXEC cannot report to APAC_WEST: APAC_WEST is already beneath EXEC.
        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.assertNoCycle(byId, exec, west));

        assertTrue(refusal.getMessage().contains("EXEC"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("APAC_WEST"), refusal.getMessage());
        assertTrue(refusal.getMessage().toLowerCase().contains("cycle"), refusal.getMessage());
    }

    @Test void reparentingARoleUnderItsDirectChildIsRejected() {
        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.assertNoCycle(byId, leadership, west));
        assertTrue(refusal.getMessage().contains("SALES_LEADERSHIP"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("APAC_WEST"), refusal.getMessage());
    }

    @Test void aRoleCannotReportToItself() {
        ConflictException refusal = assertThrows(ConflictException.class,
                () -> service.assertNoCycle(byId, west, west));
        assertTrue(refusal.getMessage().contains("APAC_WEST"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("itself"), refusal.getMessage());
    }

    @Test void movingARoleSidewaysIsAllowed() {
        // APAC_WEST under OPERATIONS_TEAM is a re-org, not a cycle.
        service.assertNoCycle(byId, west, ops);
    }

    @Test void movingASubtreeToTheRootIsAllowed() {
        service.assertNoCycle(byId, west, exec);
    }
}
