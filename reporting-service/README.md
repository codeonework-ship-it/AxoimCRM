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

The CRM API exposes an authenticated inline-PDF read endpoint at
`/api/v1/reports/{code}/document-preview`. It renders the same Jasper query as
the download endpoint but does not record an export or require export rights.
The web client loads the bytes with its bearer token and gives the resulting
object URL to its bundled PDF.js canvas viewer; this avoids putting credentials
in a URL and does not depend on a browser or Electron PDF plug-in.

Third-party delivery remains intentionally pending. Report alert dispatches are
queued through the CRM outbox and can later be consumed by an SMTP/provider worker.
