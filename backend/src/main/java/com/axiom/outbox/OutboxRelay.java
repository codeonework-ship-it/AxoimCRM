package com.axiom.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox and publishes to Kafka (ADR-003). Deliberately runs with
 * NO tenant context: it serves all tenants, so it uses the dedicated relay
 * datasource (axiom_relay role, V3 migration) whose RLS policies permit
 * cross-tenant SELECT/UPDATE on outbox_event only.
 *
 * Delivery contract:
 * <ul>
 *   <li>FOR UPDATE SKIP LOCKED — safe under multiple instances; two relays
 *       never double-claim a row within a tick.</li>
 *   <li>Synchronous send with timeout; a row is marked dispatched only AFTER
 *       the broker acked. If Kafka is down we log a warning and leave the rows
 *       untouched for the next tick — the outbox never loses an event; the
 *       broker may see a duplicate (at-least-once, consumers are idempotent).</li>
 *   <li>Key = tenantId:aggregateId — per-record ordering per ADR-003 rule 4.</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate relayJdbc;
    private final TransactionTemplate relayTx;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(@Qualifier("relayJdbcTemplate") JdbcTemplate relayJdbc,
                       @Qualifier("relayTransactionTemplate") TransactionTemplate relayTx,
                       KafkaTemplate<String, String> kafka,
                       @Value("${axiom.outbox.topic}") String topic,
                       @Value("${axiom.outbox.batch-size}") int batchSize) {
        this.relayJdbc = relayJdbc;
        this.relayTx = relayTx;
        this.kafka = kafka;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    private record OutboxRow(UUID id, UUID tenantId, UUID aggregateId, String eventType, String payload) {}

    @Scheduled(fixedDelayString = "${axiom.outbox.poll-fixed-delay-ms}")
    public void relayTick() {
        try {
            Integer dispatched = relayTx.execute(status -> {
                List<OutboxRow> rows = relayJdbc.query(
                        "select id, tenant_id, aggregate_id, event_type, payload::text as payload "
                                + "from outbox_event where dispatched_at is null "
                                + "order by created_at limit ? for update skip locked",
                        (rs, i) -> new OutboxRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getString("event_type"),
                                rs.getString("payload")),
                        batchSize);
                if (rows.isEmpty()) {
                    return 0;
                }

                List<UUID> sent = new ArrayList<>();
                for (OutboxRow row : rows) {
                    String key = row.tenantId() + ":" + row.aggregateId();
                    try {
                        // Sync send: the broker ack is the precondition for marking dispatched.
                        kafka.send(topic, key, row.payload()).get(10, TimeUnit.SECONDS);
                        sent.add(row.id());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        // Kafka unavailable or slow: keep what we already sent, leave the
                        // rest undispatched for the next tick. Never lose the row.
                        log.warn("Outbox relay: Kafka publish failed for event {} ({}); will retry next tick: {}",
                                row.id(), row.eventType(), e.getMessage());
                        break;
                    }
                }

                if (!sent.isEmpty()) {
                    relayJdbc.update(
                            "update outbox_event set dispatched_at = ? where id = any(?)",
                            ps -> {
                                ps.setTimestamp(1, Timestamp.from(java.time.Instant.now()));
                                ps.setArray(2, ps.getConnection().createArrayOf("uuid", sent.toArray()));
                            });
                }
                return sent.size();
            });
            if (dispatched != null && dispatched > 0) {
                log.info("Outbox relay: dispatched {} event(s) to {}", dispatched, topic);
            }
        } catch (Exception e) {
            // e.g. database unavailable — log and let the next tick retry.
            log.warn("Outbox relay tick failed; will retry: {}", e.getMessage());
        }
    }
}
