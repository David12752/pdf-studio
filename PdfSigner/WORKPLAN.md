# PDF Studio - Work Plan (Android + Desktop)

## 1) Product & Scope Alignment (Week 1)
- Declare Electron as the official desktop target.
- Freeze/deprecate legacy Compose desktop launcher.
- Define parity matrix (Android vs Desktop) for every feature and edge-case.
- Define acceptance criteria per feature (UX, performance, security, failure states).

## 2) Security Hardening (Weeks 1-3)
### Desktop (Critical)
- Enable `contextIsolation=true`, disable `nodeIntegration` in renderer.
- Use `preload` + strict IPC/API surface (allowlist only).
- Remove direct `window.require` access from renderer.
- Add CSP and sanitize all external input/deep link paths.
- Replace ad-hoc protection mode label with explicit UX wording.

### Android
- Add certificate pinning for remote API.
- Add secure storage policy (tokens/session metadata).
- Add deep-link validation hardening + malformed-link handling.

## 3) Architecture Refactor (Weeks 2-6)
- Split Electron renderer monolith into modules:
  - `core/state`
  - `core/pdf`
  - `core/tools`
  - `core/remote`
  - `ui/*`
- Define service contracts for shared behavior between Android and Desktop.
- Continue KMP migration only where strong ROI exists (models + business rules).

## 4) UX/UI Improvements (Weeks 3-7)
- Introduce unified design tokens (spacing, typography, states).
- Add loading/empty/error/success states for each flow.
- Accessibility pass (keyboard map, tab order, focus rings, labels).
- Improve readability and workflow guidance for power tools.

## 5) Reliability, QA, and DevEx (Weeks 1-8)
- Add unit tests for pure logic (tools parsing, deep links, export transforms).
- Add smoke E2E for critical flows (open -> sign -> export, merge, remote session).
- Add CI pipeline for build/lint/test/security audit.
- Add release checklist and rollback procedure.

## 6) Performance (Weeks 4-8)
- Move heavy PDF tasks off renderer main thread.
- Add file-size safeguards and progress indicators.
- Benchmark large files (100MB+, 1000 pages) and optimize hotspots.

## 7) Compliance & Product Messaging (Weeks 2-5)
- Distinguish clearly: visual signature vs cryptographic digital signature.
- Add legal/compliance copy for remote-sign flow and data retention.
- Define enterprise roadmap for certificate-based signing.

## Immediate Sprint (Started Now)
1. Harden Electron runtime security (preload bridge, no direct Node in renderer).
2. Stabilize run/build commands from workspace root and module root.
3. Rebuild and verify installer outputs.

## Progress Update (Current)
- Completed: Electron security hardening (`contextIsolation=true`, `nodeIntegration=false`, preload API bridge).
- Completed: Root-level `npm` scripts (`dev/test/build/dist`) to avoid command-path failures.
- Completed: Deep-link parser hardening and extraction into tested pure module.
- Completed: Unit test baseline (`tests/pure.test.js`) and script wiring.
- Completed: CI workflow for desktop (`.github/workflows/desktop-ci.yml`) with install+test+build.
- Completed: CSP added in desktop renderer HTML.
- Completed: Legacy Compose desktop launcher message updated to point to Electron target.
- Completed: Worker-based offloading for heavy tools (`merge/split/extract`) via `renderer/workers/pdfToolsWorker.js`.
- Completed: Added pure tests for export coordinate transform and remote state transitions.
- Completed: Added release verify gate (`test + audit:ci`) and integrated audit gate in CI.
- Completed: Added file-size safeguards and operation status feedback for heavy tools.
- **Completed: Full Remote Signing System** — HTTP server (`server/server.js`), PDF signature detection
  (`server/pdfProcessor.js`), nodemailer email (`server/emailer.js`), beautiful Hebrew signing landing
  page (`server/public/sign.html`), localtunnel public access, IPC bridge in `main.js` + `preload.js`,
  full `features/remote.js` rewrite, session history with download. 7/7 tests pass, build ✅.

## Next Execution Batch
1. Continue renderer modularization (`app.js` split into `sign.js`/`tools.js`/`settings.js`).
2. Address 23 high-severity npm vulnerabilities (pdfjs-dist/electron-builder update).
3. Move `compress`/`watermark` operations to Web Worker.
4. Android security hardening (cert pinning, secure storage).

