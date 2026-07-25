# Axiom Reporting Service

This is the standalone Jasper Reports project boundary for Axiom CRM.

The API currently renders the same JRXML templates in-process so the Reports module
can download PDF, Excel and Word immediately. This project keeps reporting assets
and exporter logic ready to become an independent service when deployment needs
require isolated scaling.

Current template:

- `src/main/resources/reports/tenant-summary.jrxml`

Supported formats:

- PDF
- XLSX
- DOCX

Third-party delivery remains intentionally pending. Report alert dispatches are
queued through the CRM outbox and can later be consumed by an SMTP/provider worker.
