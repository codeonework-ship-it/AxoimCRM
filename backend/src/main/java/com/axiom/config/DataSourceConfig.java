package com.axiom.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Dual datasource wiring (ADR-001 / ADR-003):
 *
 * <ul>
 *   <li><b>Primary</b> — connects as least-privilege {@code axiom_app}; every
 *       query is subject to the tenant RLS policies. Used by JPA and by the
 *       default {@link JdbcTemplate} (including TenantSessionAspect and
 *       OutboxWriter).</li>
 *   <li><b>Relay</b> — connects as {@code axiom_relay} (V3 migration), whose
 *       only capability is SELECT/UPDATE on {@code outbox_event} across all
 *       tenants. Used exclusively by OutboxRelay, which by design has no
 *       tenant context.</li>
 * </ul>
 *
 * Defining our own DataSource beans backs off Spring Boot's datasource
 * auto-configuration, so the primary is declared explicitly and marked
 * {@code @Primary} to keep JPA/Flyway wiring unchanged.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource appDataSource(DataSourceProperties appDataSourceProperties) {
        return appDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource appDataSource) {
        return new JdbcTemplate(appDataSource);
    }

    @Bean
    @ConfigurationProperties("axiom.relay.datasource")
    public DataSourceProperties relayDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "relayDataSource")
    public HikariDataSource relayDataSource(
            @Qualifier("relayDataSourceProperties") DataSourceProperties relayDataSourceProperties) {
        HikariDataSource ds = relayDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("axiom-relay-pool");
        ds.setMaximumPoolSize(2); // one poller; keep the footprint minimal
        return ds;
    }

    @Bean(name = "relayJdbcTemplate")
    public JdbcTemplate relayJdbcTemplate(@Qualifier("relayDataSource") DataSource relayDataSource) {
        return new JdbcTemplate(relayDataSource);
    }

    /**
     * Explicit primary JPA transaction manager. Required because declaring the
     * relay's DataSourceTransactionManager below backs off Boot's
     * auto-configured one ({@code @ConditionalOnMissingBean(TransactionManager)}).
     * All {@code @Transactional} business code uses this one.
     */
    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }

    @Bean(name = "relayTransactionManager")
    public PlatformTransactionManager relayTransactionManager(
            @Qualifier("relayDataSource") DataSource relayDataSource) {
        return new DataSourceTransactionManager(relayDataSource);
    }

    /** Programmatic transactions for the relay's poll-lock-publish-mark cycle. */
    @Bean(name = "relayTransactionTemplate")
    public TransactionTemplate relayTransactionTemplate(
            @Qualifier("relayTransactionManager") PlatformTransactionManager relayTransactionManager) {
        return new TransactionTemplate(relayTransactionManager);
    }
}
