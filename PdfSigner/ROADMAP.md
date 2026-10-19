# PDF Studio — Roadmap & Implementation Plan

> **Status legend:** ? Planned · ?? In Progress · ? Done

---

## Phase 1 — Remote Signing: Backend API ?
**Goal:** Lightweight Ktor server — stores only session tokens + doc hash, never the document itself.

### 1.1 Project scaffold
- [ ] Create `backend/` Kotlin/Ktor module (standalone JAR)
- [ ] Add dependencies: Ktor, Exposed ORM, PostgreSQL JDBC, jjwt, BCrypt, FCM Admin SDK
- [ ] Configure Dockerfile + docker-compose.yml

### 1.2 Data model
- [ ] `SigningSession` table: `id`, `creatorToken`, `signerToken`, `docHash (SHA-256)`, `docName`, `status (PENDING/SIGNED/EXPIRED)`, `expiresAt`, `createdAt`
- [ ] No PDF bytes stored on server — zero document retention

### 1.3 REST endpoints
| Method | Path | Description |
|--------|------|-------------|
| POST | `/sessions` | Sender creates session ? returns `sessionId + signerLink` |
| GET | `/sessions/{id}` | Poll status (sender) |
| GET | `/sessions/{id}/download` | Download signed PDF (pre-signed S3 URL or direct) |
| POST | `/sessions/{id}/sign` | Signer uploads signed PDF |
| DELETE | `/sessions/{id}` | Cancel session |

### 1.4 Auth & security
- [ ] JWT bearer tokens (creator + signer roles, different keys)
- [ ] HTTPS via Nginx reverse proxy + Let's Encrypt
- [ ] Rate limiting: 10 req/min per IP
- [ ] Session expiry: 24h default, configurable up to 72h

### 1.5 Push notifications
- [ ] FCM: notify sender when document is signed
- [ ] FCM: notify signer when invited to sign

---

## Phase 2 — Remote Signing: Android Sender Flow ?
**Goal:** User can send a signing request from inside PDF Studio.

### 2.1 New files
- [ ] `domain/model/RemoteSession.kt` — session data class
- [ ] `domain/service/RemoteSigningService.kt` — API calls (Retrofit/Ktor client)
- [ ] `domain/repository/RemoteSessionRepository.kt`
- [ ] `ui/viewmodel/RemoteSignViewModel.kt`
- [ ] `ui/screens/RemoteSignScreen.kt`

### 2.2 Sender UI flow
- [ ] Entry point: "Send for Remote Signing" button in `DocumentViewerScreen`
- [ ] Input: signer email/phone + optional message
- [ ] Upload: hash of PDF sent to backend (not the file)
- [ ] Display: sharing deep link + QR code to signer
- [ ] Live status: polling every 10s or FCM push
- [ ] On signed: download button + local notification

### 2.3 Navigation
- [ ] Add `Routes.REMOTE_SIGN = "remote_sign/{sessionId}"` to `PdfSignerNavHost`
- [ ] Add entry in bottom sheet from viewer

---

## Phase 3 — Remote Signing: Android Signer Flow ?
**Goal:** Recipient opens the deep link, signs, and uploads.

### 3.1 Deep link handling
- [ ] Register `intent-filter` in `AndroidManifest.xml` for `pdfsigner://sign/{sessionId}`
- [ ] Handle in `MainActivity` ? navigate to signer screen

### 3.2 Signer UI flow
- [ ] `ui/screens/RemoteSignerScreen.kt`
- [ ] Download doc metadata (name, page count, hash)
- [ ] Show signature canvas (reuse `SignatureDrawingView`)
- [ ] On confirm: stamp signature ? SHA-256 check ? upload signed bytes
- [ ] Success state with confirmation animation

---

## Phase 4 — Security Hardening ?

- [ ] TLS certificate pinning on all API calls (OkHttp `CertificatePinner`)
- [ ] Signed PDF integrity check: verify SHA-256 matches pre-sign hash before upload
- [ ] Optional: embed RSA/EC digital signature metadata in PDF using BouncyCastle
- [ ] Obfuscate API base URL in BuildConfig (not plain string)
- [ ] Unit tests for hash verification logic

---

## Phase 5 — Multiplatform Module Migration ?
**Goal:** Restructure into Kotlin Multiplatform so Android + Desktop share 70%+ code.

### 5.1 Module structure
```
shared/
  commonMain/
    domain/model/          ? move all data classes here
    domain/service/        ? expect/actual interfaces
    ui/viewmodel/          ? commonMain ViewModels (lifecycle-viewmodel-compose multiplatform)
  androidMain/
    service/impl/          ? PDFBox-Android implementations
    platform/FileAccess.kt ? ContentResolver
  desktopMain/
    service/impl/          ? Apache PDFBox standard implementations
    platform/FileAccess.kt ? java.io.File chooser
androidApp/                ? thin launcher (existing app module)
desktopApp/                ? new desktop launcher
```

### 5.2 Gradle changes
- [ ] `settings.gradle.kts`: add `shared`, `desktopApp` modules
- [ ] `shared/build.gradle.kts`: `kotlin("multiplatform")` + `compose.multiplatform`
- [ ] Move `domain/model/*.kt` to `shared/commonMain`
- [ ] Create `expect class PdfRenderer` + Android/Desktop `actual`

### 5.3 Dependency updates
- [ ] Add `org.jetbrains.compose` plugin
- [ ] Add `androidx.lifecycle:lifecycle-viewmodel` multiplatform artifact
- [ ] Desktop: `org.apache.pdfbox:pdfbox:3.0.x` (standard Java)

---

## Phase 6 — Desktop Launcher ?
**Goal:** Functional Windows/macOS/Linux app from the same codebase.

### 6.1 New files
- [ ] `desktopApp/src/main/kotlin/Main.kt` — `application { Window(...) }` entry point
- [ ] `desktopApp/src/main/kotlin/DesktopFileAccess.kt` — `JFileChooser` wrapper
- [ ] `desktopApp/src/main/kotlin/DesktopPdfRenderer.kt` — Apache PDFBox `PDFRenderer`
- [ ] `desktopApp/src/main/resources/` — app icon (PNG 512ª512)

### 6.2 Desktop-specific UI
- [ ] `MenuBar`: File ? Open, Save, Export; Edit ? Undo; Help ? About
- [ ] Drag-and-drop PDF onto window to open
- [ ] `WindowState` remembering last size/position via `Preferences`
- [ ] System tray icon with "Quick Sign" action

### 6.3 Keyboard shortcuts
- [ ] `Ctrl+O` ? open file
- [ ] `Ctrl+S` ? save/export signed PDF
- [ ] `Ctrl+Z` ? undo last annotation
- [ ] `Ctrl+F` ? search in PDF text
- [ ] `Ctrl+P` ? print

### 6.4 Packaging
- [ ] `.exe` installer — Windows (NSIS via Compose Desktop Gradle plugin)
- [ ] `.dmg` — macOS (notarized)
- [ ] `.deb` / `.rpm` — Linux

---

## Phase 7 — Desktop Polish ?

- [ ] OS-native dark/light theme detection (`java.awt.Taskbar`)
- [ ] Recent files via `java.util.prefs.Preferences` (persistent across restarts)
- [ ] Auto-update check on startup (GitHub Releases API)
- [ ] Accessibility: keyboard focus rings, screen-reader labels
- [ ] Localisation: reuse existing `strings.xml` via Compose resource system

---

## Overall Timeline

| Phase | Description | Est. Duration |
|-------|-------------|---------------|
| 1 | Backend API | 4–6 weeks |
| 2 | Android sender flow | 2–3 weeks |
| 3 | Android signer flow | 2–3 weeks |
| 4 | Security hardening | 1–2 weeks |
| 5 | Multiplatform migration | 3–4 weeks |
| 6 | Desktop launcher | 2–3 weeks |
| 7 | Desktop polish | 2 weeks |
| **Total** | | **~4–5 months** |

> **Recommended order:** Phase 5 ? 6 ? 7 ? 1 ? 2 ? 3 ? 4
> Reason: doing the multiplatform refactor first means remote signing is built once
> and works on both Android and Desktop without duplication.

---

## Implementation Progress (current session)

### ? Already completed (pre-roadmap bug fixes)
- `PdfPickerViewModel`: `filteredFiles` converted to reactive `StateFlow`
- `PdfPickerViewModel`: thumbnail matching by URI (was by index — caused wrong thumbnails)
- `DocumentViewerViewModel`: `renderedPages` updates use O(n) mutable list (was O(n²) `mapIndexed`)
- `DocumentViewerViewModel`: stream properly closed with `use {}` in `loadPdf`

### ? Completed this session (Phase 2 + 3 scaffold)
- `domain/model/RemoteSession.kt` — session data class + `RemoteSessionStatus` enum
- `domain/service/RemoteSigningService.kt` — full service: SHA-256 hash, session CRUD, SharedPrefs persistence, serialisation DTO
- `ui/viewmodel/RemoteSignViewModel.kt` — sender + signer state, live polling (10s interval), session history
- `ui/screens/RemoteSignScreen.kt` — sender flow: email form, privacy notice, deep-link card, status badge, share + copy, signed-doc download, session history
- `ui/screens/RemoteSignerScreen.kt` — signer flow: session load, doc info card, message display, hash verification badge, CTA, success/expired/cancelled states
- `PdfSignerNavHost.kt` — `REMOTE_SIGN` + `REMOTE_SIGNER` routes wired
- `DocumentViewerScreen.kt` — "Send for Signing" chip in bottom action bar (PDF only)
- `strings.xml` — all remote signing strings added

### ? Completed this session (Phase 5: Multiplatform Migration)
- `settings.gradle.kts` & `build.gradle.kts` updated with KMP and Compose Multiplatform plugins
- `shared` module created with `commonMain`, `androidMain`, `desktopMain`
- `desktopApp` module created with Compose Desktop window entry point
- Domain models moved to `shared/commonMain`: `PdfDocument`, `ExportFormat`, `RemoteSession`, `SignaturePlacement`, `TextStampPlacement`, `RecentFile`, `SavedSignature`
- `PlatformUri` and `PlatformBitmap` expect/actual classes created to abstract Android `Uri` and `Bitmap`
- `PlatformContext` expect/actual created (Android `Context` / desktop no-op)
- `PdfSignatureService` expect/actual: Android (PDFBox-Android) + Desktop (Apache PDFBox 3.x)
- `MediaStoreExporter` expect/actual: Android (MediaStore) + Desktop (~/Documents/PDF Studio/)
- `RecentFilesManager` expect/actual: Android (SharedPreferences) + Desktop (java.util.prefs)
- `SavedSignaturesManager` expect/actual: Android (filesDir PNG) + Desktop (~/Documents/PDF Studio/signatures/)
- `RemoteSigningService` implemented in `androidMain` + `desktopMain` (regular classes, no expect needed until Phase 1 backend is built)
- Duplicate `app/domain/service/RemoteSigningService.kt` removed — `RemoteSignViewModel` now resolves from `:shared`
- ViewModels split: state-only data classes in `shared/commonMain`, Android implementations in `androidMain` or `app/`
- `strings.xml` (Hebrew `values-iw`) — all 25 remote signing strings translated

### ?? Next steps
- Phase 1: Backend API (Ktor server) — `backend/` module with REST endpoints, JWT, FCM
- Phase 4: Security hardening (certificate pinning, hash verification on submit, BouncyCastle RSA/EC)
- Phase 5 remainder: `expect class PdfRenderer` for page rendering, complete ViewModel migration to `commonMain`
- Phase 6–7: Full desktop launcher (MenuBar, drag-and-drop, keyboard shortcuts, packaging)
