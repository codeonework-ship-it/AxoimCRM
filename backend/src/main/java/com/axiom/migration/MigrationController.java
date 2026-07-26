package com.axiom.migration;

import com.axiom.common.ApiError;
import com.axiom.migration.MigrationConnectionService.ConnectRequest;
import com.axiom.migration.MigrationConnectionService.ConnectionRow;
import com.axiom.migration.MigrationConnectionService.DiscoveryResult;
import com.axiom.migration.MigrationConnectionService.VendorRow;
import com.axiom.migration.MigrationModel.PreFlightReport;
import com.axiom.migration.MigrationModel.ReconciliationReport;
import com.axiom.migration.MigrationModel.RollbackPreview;
import com.axiom.migration.MigrationModel.RunHandle;
import com.axiom.migration.MigrationOnboardingService.Checklist;
import com.axiom.migration.MigrationOnboardingService.TemplateRow;
import com.axiom.migration.MigrationPlanService.CreatePlanRequest;
import com.axiom.migration.MigrationPlanService.MappingEdit;
import com.axiom.migration.MigrationPlanService.MappingReview;
import com.axiom.migration.MigrationPlanService.PlanRow;
import com.axiom.migration.TargetSchema.TargetEntity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The migration engine's HTTP surface (FR-MIG-001..010).
 *
 * <p>Every long operation returns a job handle rather than a result: POST a run,
 * get a {@link RunHandle}, poll it. Nothing on this controller blocks on source
 * or target data.
 */
@RestController
@RequestMapping("/api/v1/migration")
@Validated
public class MigrationController {

    private final MigrationConnectionService connections;
    private final MigrationPlanService plans;
    private final MigrationRunService runs;
    private final MigrationRollbackService rollback;
    private final MigrationReconciler reconciler;
    private final MigrationOnboardingService onboarding;

    public MigrationController(MigrationConnectionService connections, MigrationPlanService plans,
                               MigrationRunService runs, MigrationRollbackService rollback,
                               MigrationReconciler reconciler, MigrationOnboardingService onboarding) {
        this.connections = connections;
        this.plans = plans;
        this.runs = runs;
        this.rollback = rollback;
        this.reconciler = reconciler;
        this.onboarding = onboarding;
    }

    // ------------------------------------------------------------------ sources

    @GetMapping("/vendors")
    public Map<String, Object> vendors() {
        List<VendorRow> vendors = connections.vendors();
        return Map.of("vendors", vendors, "fixtureKeys", connections.fixtureKeys());
    }

    @GetMapping("/connections")
    public List<ConnectionRow> listConnections() {
        return connections.list();
    }

    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionRow connect(@RequestBody @Valid ConnectRequest request) {
        return connections.connect(request);
    }

    @GetMapping("/connections/{id}")
    public ConnectionRow connection(@PathVariable UUID id) {
        return connections.connection(id);
    }

    @PostMapping("/connections/{id}/discover")
    public DiscoveryResult discover(@PathVariable UUID id) {
        return connections.discover(id);
    }

    /** Fixture sources only — simulates the source moving on during a parallel run. */
    @PostMapping("/connections/{id}/fixture-wave")
    public ConnectionRow fixtureWave(@PathVariable UUID id, @RequestBody Map<String, Integer> body) {
        return connections.advanceFixtureWave(id, body.getOrDefault("wave", 1));
    }

    // ------------------------------------------------------------------ target schema

    /** What a mapping may point at. Drives the mapping editor's field pickers. */
    @GetMapping("/target-schema")
    public List<TargetEntity> targetSchema() {
        return TargetSchema.entities();
    }

    // ------------------------------------------------------------------ plans and mapping

    @GetMapping("/plans")
    public List<PlanRow> listPlans() {
        return plans.list();
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanRow createPlan(@RequestBody @Valid CreatePlanRequest request) {
        return plans.create(request);
    }

    @GetMapping("/plans/{id}")
    public PlanRow plan(@PathVariable UUID id) {
        return plans.plan(id);
    }

    @GetMapping("/plans/{id}/mapping")
    public MappingReview mapping(@PathVariable UUID id) {
        return plans.review(id);
    }

    @PostMapping("/plans/{id}/mapping/propose")
    public MappingReview propose(@PathVariable UUID id) {
        return plans.propose(id);
    }

    @PatchMapping("/plans/{id}/mapping")
    public MappingReview editMapping(@PathVariable UUID id, @RequestBody @Valid List<MappingEdit> edits) {
        return plans.edit(id, edits);
    }

    @PostMapping("/plans/{id}/mapping/acknowledge")
    public MappingReview acknowledge(@PathVariable UUID id) {
        return plans.acknowledgeUnmapped(id);
    }

    // ------------------------------------------------------------------ runs

    @PostMapping("/plans/{id}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunHandle queue(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        return runs.queue(id, body.get("mode"));
    }

    @GetMapping("/plans/{id}/runs")
    public List<RunHandle> runs(@PathVariable UUID id) {
        return runs.runs(id);
    }

    @GetMapping("/runs/{runId}")
    public RunHandle run(@PathVariable UUID runId) {
        return runs.run(runId);
    }

    /** The FR-MIG-003 pre-flight report for a dry run (and the issue log for any run). */
    @GetMapping("/runs/{runId}/report")
    public PreFlightReport report(@PathVariable UUID runId) {
        return runs.report(runId);
    }

    /** The FR-MIG-006 reconciliation report. */
    @GetMapping("/runs/{runId}/reconciliation")
    public ReconciliationReport reconciliation(@PathVariable UUID runId) {
        return reconciler.report(runId);
    }

    // ------------------------------------------------------------------ rollback

    @GetMapping("/plans/{id}/rollback-preview")
    public RollbackPreview rollbackPreview(@PathVariable UUID id) {
        return rollback.preview(id);
    }

    // ------------------------------------------------------------------ onboarding

    @GetMapping("/onboarding")
    public Checklist checklist(@RequestParam(required = false) String role) {
        return onboarding.checklist(role);
    }

    @PatchMapping("/onboarding/{taskId}")
    public Checklist completeTask(@PathVariable UUID taskId, @RequestBody Map<String, Boolean> body) {
        return onboarding.complete(taskId, Boolean.TRUE.equals(body.get("completed")));
    }

    @GetMapping("/templates")
    public List<TemplateRow> templates() {
        return onboarding.templates();
    }

    @PostMapping("/templates/{templateKey}/adopt")
    public TemplateRow adopt(@PathVariable String templateKey) {
        return onboarding.adopt(templateKey);
    }

    @PostMapping("/sample-data")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunHandle installSampleData() {
        return onboarding.installSampleData();
    }

    // ------------------------------------------------------------------ deferred capabilities

    /**
     * 501, with the reason named. A vendor adapter that has never completed an
     * authenticated round-trip says so rather than failing later in a way the
     * operator has to interpret.
     */
    @ExceptionHandler(MigrationNotAvailableException.class)
    public ResponseEntity<ApiError> notAvailable(MigrationNotAvailableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(ApiError.of("NOT_IMPLEMENTED", ex.getMessage(), null));
    }
}
