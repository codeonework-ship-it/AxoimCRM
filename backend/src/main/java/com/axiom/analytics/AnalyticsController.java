package com.axiom.analytics;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The reporting read-model API (E15).
 *
 * <p>Every route is behind {@code JwtAuthFilter}, so the tenant comes from the
 * verified session and never from a parameter the caller controls
 * (FR-GLOBAL-001). There is no tenant argument anywhere in this class, on purpose.
 *
 * <p>The route layout follows the module's three concerns rather than its classes:
 * {@code /reports} runs and saves queries, {@code /metrics} is the governed KPI
 * registry and its computations, {@code /projections} and {@code /snapshots} are
 * the read model's own operational surface — staleness, backfill, capture,
 * retention and drift.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@Validated
public class AnalyticsController {

    private final ReportQueryService queries;
    private final ReportViewService views;
    private final DrillThroughService drill;
    private final MetricRegistryService metrics;
    private final KpiCalculationService kpis;
    private final ProjectionStatusService projections;
    private final ProjectionBackfillService backfill;
    private final SnapshotService snapshots;
    private final ReconciliationService reconciliation;

    public AnalyticsController(ReportQueryService queries, ReportViewService views,
                               DrillThroughService drill, MetricRegistryService metrics,
                               KpiCalculationService kpis, ProjectionStatusService projections,
                               ProjectionBackfillService backfill, SnapshotService snapshots,
                               ReconciliationService reconciliation) {
        this.queries = queries;
        this.views = views;
        this.drill = drill;
        this.metrics = metrics;
        this.kpis = kpis;
        this.projections = projections;
        this.backfill = backfill;
        this.snapshots = snapshots;
        this.reconciliation = reconciliation;
    }

    // ------------------------------------------------------------------ builder metadata

    /**
     * The dataset and field catalogue that drives the builder. Served rather than
     * hard-coded in the UI so a new reportable field appears in the field picker
     * without a front-end release — and so the UI cannot offer a field the query
     * engine would refuse.
     */
    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AnalyticsDataset dataset : AnalyticsDataset.values()) {
            List<Map<String, Object>> fields = new ArrayList<>();
            for (AnalyticsDataset.Field field : dataset.fields()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", field.apiName());
                entry.put("label", ReportQueryService.humanize(field.apiName()));
                entry.put("kind", field.kind().name());
                entry.put("groupable", field.kind().groupable());
                entry.put("summable", field.kind().summable());
                fields.add(entry);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", dataset.name());
            entry.put("label", dataset.label());
            entry.put("securable", dataset.securable().map(Enum::name).orElse(null));
            entry.put("fields", fields);
            out.add(entry);
        }
        return out;
    }

    // ------------------------------------------------------------------ reports

    @PostMapping("/reports/run")
    public ReportQueryService.ReportResult run(
            @RequestBody @Valid ReportQueryService.ReportRequest request) {
        return queries.run(request);
    }

    @GetMapping("/reports")
    public List<ReportViewService.SavedReport> savedReports() {
        return views.list();
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportViewService.SavedReport save(@RequestBody @Valid ReportViewService.SaveRequest request) {
        return views.save(request);
    }

    @GetMapping("/reports/{code}")
    public ReportViewService.SavedReport savedReport(@PathVariable String code) {
        return views.byCode(code);
    }

    @PostMapping("/reports/{code}/run")
    public ReportQueryService.ReportResult runSaved(@PathVariable String code) {
        return queries.run(views.byCode(code).definition());
    }

    @DeleteMapping("/reports/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSaved(@PathVariable String code) {
        views.delete(code);
    }

    // ------------------------------------------------------------------ drill-through

    /** The contributing records behind an aggregate, each re-checked against the authoritative store. */
    @PostMapping("/drill")
    public DrillThroughService.DrillResult drill(
            @RequestBody @Valid ReportQueryService.ReportRequest request,
            @RequestParam(required = false) Integer limit) {
        return drill.records(request, limit);
    }

    /** One record, with a permission check taken now — never served from the projection. */
    @GetMapping("/drill/{dataset}/{id}")
    public Map<String, Object> drillRecord(@PathVariable String dataset, @PathVariable UUID id) {
        return drill.record(dataset, id);
    }

    // ------------------------------------------------------------------ governed metrics

    @GetMapping("/metrics")
    public List<MetricRegistryService.MetricDefinition> metricCatalogue() {
        return metrics.catalogue();
    }

    @GetMapping("/metrics/{code}")
    public MetricRegistryService.MetricDefinition metric(@PathVariable String code) {
        return metrics.active(code);
    }

    @GetMapping("/metrics/{code}/versions")
    public List<MetricRegistryService.MetricDefinition> metricVersions(@PathVariable String code) {
        return metrics.versions(code);
    }

    /** The governed publication path: retires the incumbent and activates the new version atomically. */
    @PostMapping("/metrics")
    @ResponseStatus(HttpStatus.CREATED)
    public MetricRegistryService.MetricDefinition publish(
            @RequestBody @Valid MetricRegistryService.DefinitionRequest request) {
        return metrics.publishNewVersion(request);
    }

    @PostMapping("/metrics/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public MetricRegistryService.MetricDefinition draft(
            @RequestBody @Valid MetricRegistryService.DefinitionRequest request) {
        return metrics.createDraft(request);
    }

    /**
     * Activate a draft <em>without</em> retiring the incumbent. Returns 409 when
     * one is already active — FR-RPT-009 enforced by the database, surfaced here.
     */
    @PostMapping("/metrics/{id}/activate")
    public MetricRegistryService.MetricDefinition activate(@PathVariable UUID id) {
        return metrics.activate(id);
    }

    @PostMapping("/metrics/{id}/retire")
    public MetricRegistryService.MetricDefinition retire(@PathVariable UUID id,
                                                         @RequestParam(required = false) String reason) {
        return metrics.retire(id, reason);
    }

    /** Every governed KPI, computed over the caller's permitted data. */
    @GetMapping("/kpis")
    public List<KpiCalculationService.KpiValue> kpis(
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd,
            @RequestParam(required = false) UUID ownerId) {
        return kpis.computeAll(new KpiCalculationService.KpiScope(periodStart, periodEnd, ownerId));
    }

    @GetMapping("/kpis/{code}")
    public KpiCalculationService.KpiValue kpi(@PathVariable String code,
                                              @RequestParam(required = false) LocalDate periodStart,
                                              @RequestParam(required = false) LocalDate periodEnd,
                                              @RequestParam(required = false) UUID ownerId) {
        return kpis.compute(code, new KpiCalculationService.KpiScope(periodStart, periodEnd, ownerId));
    }

    // ------------------------------------------------------------------ projection operations

    /** Per-projection staleness — the figure ADR-008 decision 5 requires to be displayed. */
    @GetMapping("/projections")
    public List<ProjectionStatusService.DatasetStaleness> projectionStatus() {
        return projections.status();
    }

    /** Rebuild the read model from current authoritative state. Works with no broker running. */
    @PostMapping("/projections/backfill")
    public ProjectionBackfillService.BackfillRun runBackfill(
            @RequestBody(required = false) @Valid ProjectionBackfillService.BackfillRequest request) {
        return backfill.run(request);
    }

    @GetMapping("/projections/backfill")
    public List<ProjectionBackfillService.BackfillRun> backfillRuns(
            @RequestParam(defaultValue = "10") int limit) {
        return backfill.recentRuns(limit);
    }

    /** Rewind the consumer cursor so the outbox is replayed from the beginning. */
    @PostMapping("/projections/replay")
    public Map<String, Object> replay(@RequestParam(required = false) String dataset) {
        return backfill.replay(dataset);
    }

    // ------------------------------------------------------------------ snapshots

    @PostMapping("/snapshots/capture")
    @ResponseStatus(HttpStatus.CREATED)
    public SnapshotService.CaptureResult capture(@RequestParam(required = false) String reason) {
        return snapshots.captureNow(reason);
    }

    @GetMapping("/snapshots/pipeline")
    public List<SnapshotService.PipelineSnapshotRow> pipelineTrend(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer limit) {
        return snapshots.pipelineTrend(from, to, limit);
    }

    @GetMapping("/snapshots/forecast")
    public List<SnapshotService.ForecastSnapshotRow> forecastSnapshots(
            @RequestParam(required = false) String periodCode,
            @RequestParam(required = false) Integer limit) {
        return snapshots.forecastSnapshots(periodCode, limit);
    }

    /** The movement waterfall between two snapshots; components reconcile to the net change. */
    @GetMapping("/snapshots/waterfall")
    public SnapshotService.Waterfall waterfall(@RequestParam UUID from, @RequestParam UUID to) {
        return snapshots.waterfall(from, to);
    }

    @GetMapping("/snapshots/retention")
    public List<Map<String, Object>> retention() {
        return snapshots.retentionPolicies();
    }

    // ------------------------------------------------------------------ reconciliation

    /** Run the drift checks now. The same checks run on a schedule (ADR-008 Compliance). */
    @PostMapping("/reconciliation/run")
    public ReconciliationService.ReconciliationReport reconcile() {
        return reconciliation.reconcileCurrentTenant();
    }

    @GetMapping("/reconciliation")
    public List<ReconciliationService.CheckResult> reconciliationHistory(
            @RequestParam(defaultValue = "20") int limit) {
        return reconciliation.recentRuns(limit);
    }
}
