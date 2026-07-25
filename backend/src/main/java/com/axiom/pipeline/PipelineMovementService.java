package com.axiom.pipeline;

import com.axiom.common.ConflictException;
import com.axiom.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pipeline movement comparison between two points in time (FR-OPP-015).
 *
 * <p>The pipeline "as of T" is reconstructed from the append-only
 * {@code opportunity_state_history} — the newest row at or before T for each
 * opportunity. Comparing two such projections is why the categories reconcile
 * exactly rather than approximately.
 *
 * <p>The categories split into two kinds, deliberately:
 * <ul>
 *   <li><b>value buckets</b> — added, grown, shrunk, won, lost, removed,
 *       unchanged. Every opportunity lands in exactly one, and its contribution
 *       is {@code (open amount at T2) - (open amount at T1)}. They therefore sum
 *       to the net change with a residual of exactly zero, which is the
 *       acceptance criterion.</li>
 *   <li><b>movement overlays</b> — advanced, moved back, slipped, pulled in.
 *       These describe the same opportunities from a different angle and would
 *       double-count if added to the value reconciliation.</li>
 * </ul>
 */
@Service
public class PipelineMovementService {

    private final JdbcTemplate jdbc;
    private final PipelineQueries queries;

    public PipelineMovementService(JdbcTemplate jdbc, PipelineQueries queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    // ------------------------------------------------------------------ records

    record Snapshot(UUID opportunityId, int stageRank, String stageName, BigDecimal amount,
                    LocalDate closeDate, boolean closed, Boolean won) {}

    public record MovementItem(UUID opportunityId, String name, String accountName, String ownerName,
                               String fromStage, String toStage,
                               BigDecimal fromAmount, BigDecimal toAmount, BigDecimal amountDelta,
                               LocalDate fromCloseDate, LocalDate toCloseDate, Integer daysMoved,
                               String category, String detail) {}

    public record Bucket(String category, String label, int count, BigDecimal amountDelta,
                         List<MovementItem> items) {}

    public record Reconciliation(BigDecimal openAmountFrom, BigDecimal openAmountTo, BigDecimal netChange,
                                 BigDecimal added, BigDecimal grown, BigDecimal shrunk, BigDecimal won,
                                 BigDecimal lost, BigDecimal removed, BigDecimal residual, boolean balanced) {}

    public record MovementReport(Instant from, Instant to, UUID pipelineId, String pipelineName,
                                 int openCountFrom, int openCountTo,
                                 Reconciliation reconciliation,
                                 List<Bucket> valueBuckets, List<Bucket> movementOverlays) {}

    // -------------------------------------------------------------------- report

    @Transactional
    public MovementReport compare(String fromRaw, String toRaw, UUID pipelineIdOrNull) {
        Instant from = parseInstant(fromRaw, "from");
        Instant to = parseInstant(toRaw, "to");
        if (!to.isAfter(from)) {
            throw new ConflictException("The 'to' point must be after the 'from' point. "
                    + "You asked to compare " + from + " with " + to + ".");
        }
        UUID pipelineId = pipelineIdOrNull != null ? pipelineIdOrNull : queries.defaultPipelineId();
        String pipelineName = queries.pipelines().stream()
                .filter(p -> p.id().equals(pipelineId))
                .map(PipelineQueries.PipelineRow::name).findFirst().orElse("Pipeline");

        backfillMissingGenesisRows(pipelineId);

        Map<UUID, Snapshot> before = asOf(pipelineId, from);
        Map<UUID, Snapshot> after = asOf(pipelineId, to);
        Map<UUID, Labels> labels = labels(pipelineId);

        Set<UUID> ids = new LinkedHashSet<>();
        ids.addAll(before.keySet());
        ids.addAll(after.keySet());

        Map<String, List<MovementItem>> value = new LinkedHashMap<>();
        Map<String, List<MovementItem>> overlay = new LinkedHashMap<>();
        BigDecimal openFrom = BigDecimal.ZERO;
        BigDecimal openTo = BigDecimal.ZERO;
        int openCountFrom = 0;
        int openCountTo = 0;

        for (UUID id : ids) {
            Snapshot a = before.get(id);
            Snapshot b = after.get(id);
            boolean openA = a != null && !a.closed();
            boolean openB = b != null && !b.closed();
            BigDecimal amountA = openA ? nz(a.amount()) : BigDecimal.ZERO;
            BigDecimal amountB = openB ? nz(b.amount()) : BigDecimal.ZERO;
            openFrom = openFrom.add(amountA);
            openTo = openTo.add(amountB);
            if (openA) openCountFrom++;
            if (openB) openCountTo++;

            BigDecimal delta = amountB.subtract(amountA);
            String category = categorize(a, b, openA, openB, delta);
            String detail = detail(category, a, b);
            value.computeIfAbsent(category, k -> new ArrayList<>())
                    .add(item(id, labels, a, b, delta, category, detail));

            if (openA && openB) {
                if (b.stageRank() > a.stageRank()) {
                    overlay.computeIfAbsent("ADVANCED", k -> new ArrayList<>()).add(item(id, labels, a, b,
                            delta, "ADVANCED", a.stageName() + " to " + b.stageName()));
                } else if (b.stageRank() < a.stageRank()) {
                    overlay.computeIfAbsent("MOVED_BACK", k -> new ArrayList<>()).add(item(id, labels, a, b,
                            delta, "MOVED_BACK", b.stageName() + " (was " + a.stageName() + ")"));
                }
                if (a.closeDate() != null && b.closeDate() != null && b.closeDate().isAfter(a.closeDate())) {
                    overlay.computeIfAbsent("SLIPPED", k -> new ArrayList<>()).add(item(id, labels, a, b,
                            delta, "SLIPPED", ChronoUnit.DAYS.between(a.closeDate(), b.closeDate())
                                    + " days later"));
                } else if (a.closeDate() != null && b.closeDate() != null && b.closeDate().isBefore(a.closeDate())) {
                    overlay.computeIfAbsent("PULLED_IN", k -> new ArrayList<>()).add(item(id, labels, a, b,
                            delta, "PULLED_IN", ChronoUnit.DAYS.between(b.closeDate(), a.closeDate())
                                    + " days earlier"));
                }
            }
        }

        List<Bucket> valueBuckets = new ArrayList<>();
        for (Map.Entry<String, String> spec : VALUE_LABELS.entrySet()) {
            List<MovementItem> items = value.getOrDefault(spec.getKey(), List.of());
            valueBuckets.add(new Bucket(spec.getKey(), spec.getValue(), items.size(), sum(items), items));
        }
        List<Bucket> overlays = new ArrayList<>();
        for (Map.Entry<String, String> spec : OVERLAY_LABELS.entrySet()) {
            List<MovementItem> items = overlay.getOrDefault(spec.getKey(), List.of());
            overlays.add(new Bucket(spec.getKey(), spec.getValue(), items.size(), sum(items), items));
        }

        BigDecimal net = openTo.subtract(openFrom);
        BigDecimal added = bucketAmount(valueBuckets, "ADDED");
        BigDecimal grown = bucketAmount(valueBuckets, "GROWN");
        BigDecimal shrunk = bucketAmount(valueBuckets, "SHRUNK");
        BigDecimal won = bucketAmount(valueBuckets, "WON");
        BigDecimal lost = bucketAmount(valueBuckets, "LOST");
        BigDecimal removed = bucketAmount(valueBuckets, "REMOVED");
        BigDecimal residual = net.subtract(added).subtract(grown).subtract(shrunk)
                .subtract(won).subtract(lost).subtract(removed);

        return new MovementReport(from, to, pipelineId, pipelineName, openCountFrom, openCountTo,
                new Reconciliation(openFrom, openTo, net, added, grown, shrunk, won, lost, removed,
                        residual, residual.signum() == 0),
                valueBuckets, overlays);
    }

    private static final Map<String, String> VALUE_LABELS = new LinkedHashMap<>(Map.of());
    private static final Map<String, String> OVERLAY_LABELS = new LinkedHashMap<>(Map.of());

    static {
        VALUE_LABELS.put("ADDED", "Added to the open pipeline");
        VALUE_LABELS.put("GROWN", "Grown in value");
        VALUE_LABELS.put("SHRUNK", "Shrunk in value");
        VALUE_LABELS.put("WON", "Won");
        VALUE_LABELS.put("LOST", "Lost");
        VALUE_LABELS.put("REMOVED", "Removed without an outcome");
        VALUE_LABELS.put("UNCHANGED", "Unchanged in value");
        OVERLAY_LABELS.put("ADVANCED", "Advanced a stage");
        OVERLAY_LABELS.put("MOVED_BACK", "Moved back a stage");
        OVERLAY_LABELS.put("SLIPPED", "Close date slipped");
        OVERLAY_LABELS.put("PULLED_IN", "Close date pulled in");
    }

    /**
     * Exactly one value bucket per opportunity — this is what makes the
     * reconciliation exact.
     */
    static String categorize(Snapshot a, Snapshot b, boolean openA, boolean openB, BigDecimal delta) {
        boolean closedInWindow = b != null && b.closed() && (a == null || !a.closed());
        if (closedInWindow) {
            return Boolean.TRUE.equals(b.won()) ? "WON" : "LOST";
        }
        if (!openA && openB) return "ADDED";
        if (openA && !openB) return "REMOVED";
        if (openA && openB && delta.signum() > 0) return "GROWN";
        if (openA && openB && delta.signum() < 0) return "SHRUNK";
        return "UNCHANGED";
    }

    private static String detail(String category, Snapshot a, Snapshot b) {
        return switch (category) {
            case "ADDED" -> a == null ? "New opportunity" : "Reopened into the pipeline";
            case "WON" -> "Closed won";
            case "LOST" -> "Closed lost";
            case "GROWN", "SHRUNK" -> (a == null ? "" : a.stageName()) + " to " + (b == null ? "" : b.stageName());
            default -> b == null ? "" : b.stageName();
        };
    }

    private MovementItem item(UUID id, Map<UUID, Labels> labels, Snapshot a, Snapshot b,
                              BigDecimal delta, String category, String detail) {
        Labels l = labels.getOrDefault(id, new Labels("(removed)", "", ""));
        Integer daysMoved = a == null || b == null || a.closeDate() == null || b.closeDate() == null
                ? null : (int) ChronoUnit.DAYS.between(a.closeDate(), b.closeDate());
        return new MovementItem(id, l.name(), l.accountName(), l.ownerName(),
                a == null ? null : a.stageName(), b == null ? null : b.stageName(),
                a == null ? null : a.amount(), b == null ? null : b.amount(), delta,
                a == null ? null : a.closeDate(), b == null ? null : b.closeDate(),
                daysMoved, category, detail);
    }

    private static BigDecimal bucketAmount(List<Bucket> buckets, String category) {
        return buckets.stream().filter(b -> b.category().equals(category))
                .map(Bucket::amountDelta).findFirst().orElse(BigDecimal.ZERO);
    }

    private static BigDecimal sum(List<MovementItem> items) {
        return items.stream().map(MovementItem::amountDelta).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ------------------------------------------------------------------ loading

    Map<UUID, Snapshot> asOf(UUID pipelineId, Instant at) {
        Map<UUID, Snapshot> out = new LinkedHashMap<>();
        jdbc.query("""
                select distinct on (h.opportunity_id)
                       h.opportunity_id, h.stage_rank, h.amount, h.close_date, h.is_closed, h.is_won,
                       s.name as stage_name
                from sales.opportunity_state_history h
                join sales.opportunity o on o.tenant_id = h.tenant_id and o.id = h.opportunity_id
                join crm.pipeline_stage s on s.tenant_id = h.tenant_id and s.id = h.stage_id
                where h.tenant_id = ? and o.pipeline_id = ? and h.observed_at <= ?
                order by h.opportunity_id, h.observed_at desc, h.id desc
                """, rs -> {
            out.put(rs.getObject("opportunity_id", UUID.class), new Snapshot(
                    rs.getObject("opportunity_id", UUID.class),
                    rs.getInt("stage_rank"),
                    rs.getString("stage_name"),
                    rs.getBigDecimal("amount"),
                    rs.getObject("close_date", LocalDate.class),
                    rs.getBoolean("is_closed"),
                    rs.getObject("is_won") == null ? null : rs.getBoolean("is_won")));
        }, TenantContext.get().tenantId(), pipelineId, java.sql.Timestamp.from(at));
        return out;
    }

    record Labels(String name, String accountName, String ownerName) {}

    private Map<UUID, Labels> labels(UUID pipelineId) {
        Map<UUID, Labels> out = new LinkedHashMap<>();
        jdbc.query("""
                select o.id, o.name, a.name as account_name, coalesce(u.display_name, '') as owner_name
                from sales.opportunity o
                join crm.account a on a.tenant_id = o.tenant_id and a.id = o.account_id
                left join identity.app_user u on u.tenant_id = o.tenant_id and u.id = o.owner_id
                where o.tenant_id = ? and o.pipeline_id = ?
                """, rs -> {
            out.put(rs.getObject("id", UUID.class), new Labels(
                    rs.getString("name"), rs.getString("account_name"), rs.getString("owner_name")));
        }, TenantContext.get().tenantId(), pipelineId);
        return out;
    }

    /**
     * Self-heals the baseline. Opportunities created before E06, or by a writer
     * that predates it, would otherwise be invisible to the comparison. Better
     * to record "we first observed it at creation with these values" than to
     * silently drop it from a reconciliation that claims to balance.
     */
    private void backfillMissingGenesisRows(UUID pipelineId) {
        jdbc.update("""
                insert into sales.opportunity_state_history
                  (tenant_id, opportunity_id, observed_at, change_kind, stage_id, stage_rank,
                   amount, close_date, is_closed, is_won)
                select o.tenant_id, o.id, o.created_at, 'GENESIS_BACKFILL', o.stage_id,
                       (select count(*) + 1 from crm.pipeline_stage s2
                         where s2.tenant_id = o.tenant_id and s2.pipeline_id = o.pipeline_id
                           and s2.deleted_at is null and s2.sort_order < s.sort_order)::int,
                       o.amount, o.close_date, o.is_closed, o.is_won
                from sales.opportunity o
                join crm.pipeline_stage s on s.tenant_id = o.tenant_id and s.id = o.stage_id
                where o.tenant_id = ? and o.pipeline_id = ?
                  and not exists (select 1 from sales.opportunity_state_history h
                                  where h.tenant_id = o.tenant_id and h.opportunity_id = o.id)
                """, TenantContext.get().tenantId(), pipelineId);
    }

    /** Accepts either a plain date or a full ISO instant, so the API is usable by hand. */
    static Instant parseInstant(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new ConflictException("The '" + field + "' point in time is required. "
                    + "Send a date (2026-07-01) or an ISO timestamp (2026-07-01T00:00:00Z).");
        }
        try {
            if (value.length() == 10) {
                return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            if (value.endsWith("Z") || value.contains("+")) {
                return OffsetDateTime.parse(value).toInstant();
            }
            return OffsetDateTime.of(java.time.LocalDateTime.parse(value), ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ex) {
            throw new ConflictException("'" + raw + "' is not a date or timestamp we can read for '" + field
                    + "'. Use 2026-07-01 or 2026-07-01T00:00:00Z.");
        }
    }
}
