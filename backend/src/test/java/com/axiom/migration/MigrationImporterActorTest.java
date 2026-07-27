package com.axiom.migration;

import com.axiom.outbox.OutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MigrationImporterActorTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLATFORM_ACTOR = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT_ADMIN = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void platformRequestUsesActiveTenantOwnerForBusinessForeignKeys() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(TENANT), eq(PLATFORM_ACTOR)))
                .thenReturn(List.of(TENANT_ADMIN));
        MigrationImporter importer = new MigrationImporter(jdbc, mock(MigrationAnalyzer.class),
                mock(OutboxWriter.class));

        assertThat(importer.tenantActor(TENANT, PLATFORM_ACTOR)).isEqualTo(TENANT_ADMIN);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test void importStopsWithOperatorGuidanceWhenTenantHasNoActiveOwner() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(TENANT), eq(PLATFORM_ACTOR)))
                .thenReturn(List.of());
        MigrationImporter importer = new MigrationImporter(jdbc, mock(MigrationAnalyzer.class),
                mock(OutboxWriter.class));

        assertThatThrownBy(() -> importer.tenantActor(TENANT, PLATFORM_ACTOR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Activate a tenant administrator");
    }
}
