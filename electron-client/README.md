# Axiom CRM — Desktop Shell

Minimal Electron wrapper around the Axiom CRM web app. It adds a native
window, a standard app menu (reload / devtools / zoom), and native OS
notifications exposed to the web app as `window.axiomDesktop.notify(title, body)`.

The local publish flow creates a portable Windows desktop folder and zip from
the current frontend production build. It is unsigned by design; code signing
and store publishing require external certificates/accounts and remain a
release-management step outside this repository.

## Prerequisites

- Node 18+ and npm
- The frontend, either:
  - running as a dev server: `cd ../frontend && npm run dev` (port 5173), or
  - built to static files: `cd ../frontend && npm run build` (creates `frontend/dist`)

## Run

```sh
npm install

# Development (loads http://localhost:5173 — start the Vite dev server first)
# PowerShell:
$env:ELECTRON_DEV = "1"; npm start
# bash / cmd:
ELECTRON_DEV=1 npm start

# Production-ish (loads ../frontend/dist/index.html — run `npm run build` in frontend first)
npm start

# Docker-backed desktop preview (recommended for the complete local stack)
# PowerShell:
$env:AXIOM_WEB_URL = "http://localhost:4280"; npm start

# Local desktop package / publish artifact
cd ../frontend && npm run build
cd ../electron-client && npm run package
```

The packaged output is written to `electron-client/release/`:

- `AxiomCRM-win-x64-<version>/AxiomCRM.exe`
- `AxiomCRM-win-x64-<version>.zip`

## Notes

- Window: 1440x900, minimum 1024x700, dark ground (#0A0D14).
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
