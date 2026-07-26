package com.axiom.search;

import com.axiom.audit.AuditService;
import com.axiom.common.ForbiddenException;
import com.axiom.common.NotFoundException;
import com.axiom.security.SystemTaskRunner;
import com.axiom.tenancy.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SearchBackfillServiceTest {

    private SearchBackfillService service;

    @BeforeEach
    void setUp() {
        service = new SearchBackfillService(mock(JdbcTemplate.class), mock(SearchIndex.class),
                mock(SearchProjector.class), mock(SystemTaskRunner.class), mock(AuditService.class),
                250, 25, true);
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "SALES", "Priya Nair", "priya.nair@meridianfab.com"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a sales user cannot trigger a tenant-wide reindex")
    void salesUserCannotRequestAReindex() {
        assertThrows(ForbiddenException.class,
                () -> service.request(new SearchBackfillService.ReindexRequest(null, "curiosity")));
    }

    @Test
    @DisplayName("a read-only auditor cannot trigger a reindex either")
    void auditorCannotRequestAReindex() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "AUDITOR", "Audit User", "audit@example.com"));
        assertThrows(ForbiddenException.class,
                () -> service.request(new SearchBackfillService.ReindexRequest("ACCOUNT", null)));
    }

    @Test
    @DisplayName("an unknown entity type is refused before anything is queued")
    void unknownEntityTypeIsRefused() {
        TenantContext.set(new TenantContext.Principal(UUID.randomUUID(), UUID.randomUUID(),
                "TENANT_ADMIN", "Raj Malhotra", "raj.malhotra@meridianfab.com"));
        assertThrows(NotFoundException.class,
                () -> service.request(new SearchBackfillService.ReindexRequest("INVOICE", null)));
    }

    @Test
    @DisplayName("the indexable type catalogue is the enum, so the UI cannot drift from the engine")
    void indexableTypesComeFromTheEnum() {
        assertThat(service.indexableTypes())
                .containsExactly("ACCOUNT", "CONTACT", "LEAD", "OPPORTUNITY");
    }
}
