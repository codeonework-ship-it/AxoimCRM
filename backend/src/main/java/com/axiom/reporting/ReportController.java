package com.axiom.reporting;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {
    private final ReportService reports;
    private final ReportSubscriptionService subscriptions;

    public ReportController(ReportService reports, ReportSubscriptionService subscriptions) {
        this.reports = reports;
        this.subscriptions = subscriptions;
    }

    @GetMapping
    public List<ReportService.ReportDefinitionRow> definitions() {
        return reports.definitions();
    }

    @GetMapping("/subscriptions")
    public List<ReportSubscriptionService.Subscription> subscriptions() {
        return subscriptions.list();
    }

    @PostMapping("/subscriptions")
    public ReportSubscriptionService.Subscription createSubscription(
            @RequestBody ReportSubscriptionService.CreateRequest request) {
        return subscriptions.create(request);
    }

    @PostMapping("/subscriptions/run-due")
    public List<ReportSubscriptionService.RunResult> runDueSubscriptions() {
        return subscriptions.runDue();
    }

    @GetMapping("/{code}/download")
    public ResponseEntity<byte[]> download(@PathVariable String code,
                                           @RequestParam ReportService.ReportFormat format) {
        ReportService.FilePayload file = reports.export(code, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }

    @GetMapping("/{code}/preview")
    public ReportService.ReportPreview preview(@PathVariable String code) {
        return reports.preview(code);
    }
}
