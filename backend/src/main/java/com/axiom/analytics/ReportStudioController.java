package com.axiom.analytics;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** HTTP surface for the report builder's dashboard, sharing, embed and delivery workspaces. */
@RestController
@RequestMapping("/api/v1/analytics/studio")
public class ReportStudioController {
    private final ReportStudioService studio;

    public ReportStudioController(ReportStudioService studio) {
        this.studio = studio;
    }

    @GetMapping("/dashboards")
    public List<ReportStudioService.Dashboard> dashboards() { return studio.dashboards(); }

    @PostMapping("/dashboards")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportStudioService.Dashboard saveDashboard(@RequestBody @Valid ReportStudioService.DashboardRequest request) {
        return studio.saveDashboard(request);
    }

    @PostMapping("/dashboards/{code}/widgets")
    public ReportStudioService.Widget saveWidget(@PathVariable String code,
                                                  @RequestBody @Valid ReportStudioService.WidgetRequest request) {
        return studio.saveWidget(code, request);
    }

    @DeleteMapping("/dashboards/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveDashboard(@PathVariable String code) { studio.archiveDashboard(code); }

    @GetMapping("/shares")
    public List<ReportStudioService.Share> shares() { return studio.shares(); }

    @PostMapping("/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportStudioService.Share share(@RequestBody @Valid ReportStudioService.ShareRequest request) {
        return studio.share(request);
    }

    @DeleteMapping("/shares/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeShare(@PathVariable UUID id) { studio.revokeShare(id); }

    @GetMapping("/comments")
    public List<ReportStudioService.Comment> comments(@RequestParam String targetType,
                                                       @RequestParam String targetCode) {
        return studio.comments(targetType, targetCode);
    }

    @PostMapping("/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportStudioService.Comment comment(@RequestBody @Valid ReportStudioService.CommentRequest request) {
        return studio.comment(request);
    }

    @GetMapping("/deliveries")
    public List<ReportStudioService.DeliveryPolicy> deliveries() { return studio.deliveries(); }

    @PostMapping("/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportStudioService.DeliveryPolicy schedule(@RequestBody @Valid ReportStudioService.DeliveryRequest request) {
        return studio.schedule(request);
    }

    @PostMapping("/deliveries/evaluate")
    public List<ReportStudioService.DeliveryEvaluation> evaluateDeliveries() {
        return studio.evaluateDeliveries();
    }

    @GetMapping("/embeds")
    public List<ReportStudioService.EmbedView> embeds() { return studio.embeds(); }

    @PostMapping("/embeds")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportStudioService.EmbedView embed(@RequestBody @Valid ReportStudioService.EmbedRequest request) {
        return studio.embed(request);
    }

    @GetMapping("/performance")
    public ReportStudioService.PerformanceSummary performance() { return studio.performance(); }
}
