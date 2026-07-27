# Axiom CRM — Desktop Shell

Minimal Electron wrapper around the Axiom CRM web app. It adds a native
window, a standard app menu (reload / devtools / zoom), and native OS
notifications exposed to the web app as `window.axiomDesktop.notify(title, body)`.

The local publish flow first creates a dedicated Electron renderer build, then
creates a portable Windows desktop folder and zip. The desktop renderer uses
relative `file://` assets and an explicit local API origin; it is intentionally
different from the hosted web build. The package is unsigned by design because
code signing and store publishing require external certificates/accounts.

## Prerequisites

- Node 18+ and npm
- The frontend dev server when using development mode:
  `cd ../frontend && npm run dev` (port 5173)

## Run

```sh
npm install

# Development (loads http://localhost:5173 — start the Vite dev server first)
# PowerShell:
$env:ELECTRON_DEV = "1"; npm start
# bash / cmd:
ELECTRON_DEV=1 npm start

# Production-ish (loads the current ../frontend/dist/index.html)
npm start

# Docker-backed desktop preview (recommended for the complete local stack)
# PowerShell:
$env:AXIOM_WEB_URL = "http://localhost:4280"; npm start

# Local desktop package / publish artifact. This builds the correct desktop
# renderer automatically before packaging it.
npm run publish:local

# Responsive wrapping and raster-quality acceptance audit (requires the local
# web app on :4280 and API on :8080)
npm run audit:visual
```

The visual audit signs in with the documented demo operator (override with
`AXIOM_AUDIT_TENANT`, `AXIOM_AUDIT_EMAIL`, and `AXIOM_AUDIT_PASSWORD`), checks
Home, Contacts, Authorization, and Reports at 1024x700 through 1920x1080, and
writes screenshots plus `visual-audit.json` to `audit-output/`. Page overflow,
clipped/off-screen controls, or raster images enlarged beyond their native
resolution fail the command.

The packaged output is written to `electron-client/release/`:

- `AxiomCRM-win-x64-<version>/AxiomCRM.exe`
- `AxiomCRM-win-x64-<version>.zip`

## Notes

- Window: 1440x900 content area, minimum 1024x700, dark ground (#0A0D14).
- The preload script (`preload.cjs`) is the only bridge between the page and
  Electron; context isolation and sandboxing are on, node integration is off.
- Notifications: the web app calls `window.axiomDesktop.notify(...)` for
  toast-worthy events; on Windows the app id `com.axiom.crm` is set so toasts
  attribute correctly.
- `AXIOM_WEB_URL` lets the desktop shell load the production web container.
  This avoids `file://` routing/CORS differences during an integrated local run.
- Packaged desktop builds load the bundled static frontend from
  `resources/frontend/dist`, so they do not need Vite or Nginx running for UI
  rendering. API access still follows the frontend's configured API base URL.
