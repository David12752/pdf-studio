# PDF Studio Desktop (Electron)

Desktop app implemented on Electron with feature parity to the Android app flows:

- Home + recent files + open any file
- Sign flow: load PDF, draw signature, place overlay, text stamp, export PDF/PNG/JPG
- PDF Tools: merge, split, extract page, convert PDF→text, watermark, compress, protect, audit trail
- Document viewer summary
- Remote sign sessions (local simulation with QR/deep-link)
- Settings: theme, language (RTL/LTR), font scale

## Run

```bash
cd PdfSigner/electronApp
npm install
npm run dev
```

From workspace root you can also run:

```bash
npm --prefix "PdfSigner/electronApp" run dev
```

Run tests:

```bash
npm --prefix "PdfSigner/electronApp" run test
```

Run release gate checks:

```bash
npm --prefix "PdfSigner/electronApp" run verify
```

## Build

```bash
npm --prefix "PdfSigner/electronApp" run build
```

Build output:

- `PdfSigner/electronApp/dist/win-unpacked/PDF Studio.exe`

## Deep Link (Remote Sign)

Supported scheme:

- `pdfsigner://sign/<SESSION_ID>`

Examples on Windows:

```powershell
Start-Process "pdfsigner://sign/ABC123DEF456"
```

Behavior:

- Brings app to foreground (or launches it)
- Opens `Remote Sign` tab
- Prefills `Session ID` with the value from the deep link

Production validation (after installing NSIS build):

1. Build installer:

```bash
npm --prefix "PdfSigner/electronApp" run dist
```

2. Install setup from `PdfSigner/electronApp/dist/PDF-Studio-Setup-1.0.0.exe`
3. Close app completely
4. Run:

```powershell
Start-Process "pdfsigner://sign/ABC123DEF456"
```

Expected: installed app opens, focuses, navigates to Remote Sign, and fills Session ID.

## Notes

- `Protect` generates an encrypted `.pdfs` envelope (AES-256-GCM) for secure transport/storage.
- Remote sign is currently local simulation persisted in `~/PDF Studio/remote_sessions.json`.
- Renderer security model uses preload bridge (`preload.js`) with `contextIsolation=true` and no direct Node integration in renderer.
- Heavy PDF tools (`merge/split/extract`) are executed in a dedicated worker (`renderer/workers/pdfToolsWorker.js`) to keep UI responsive.
- Desktop CI pipeline is defined in `.github/workflows/desktop-ci.yml` (install + test + build).
