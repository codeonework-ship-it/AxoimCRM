package com.axiom.alerts;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping("/email")
    public List<AlertService.EmailAlertRow> emailAlerts() {
        return alerts.emailAlerts();
    }

    @PostMapping("/email")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertService.EmailAlertRow createEmailAlert(@RequestBody @Valid AlertService.EmailAlertRequest request) {
        return alerts.createEmailAlert(request);
    }

    @PostMapping("/email/{id}/send")
    public AlertService.DispatchResult sendEmailAlert(@PathVariable UUID id) {
        return alerts.sendEmailAlert(id);
    }

    @GetMapping("/reports")
    public List<AlertService.ReportAlertRow> reportAlerts() {
        return alerts.reportAlerts();
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertService.ReportAlertRow createReportAlert(@RequestBody @Valid AlertService.ReportAlertRequest request) {
        return alerts.createReportAlert(request);
    }

    @PostMapping("/reports/{id}/send")
    public AlertService.DispatchResult sendReportAlert(@PathVariable UUID id) {
        return alerts.sendReportAlert(id);
    }
}
