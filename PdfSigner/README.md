# PDF Signer – Android App

A clean, production-ready Android PDF signing app built with:

| Concern | Choice |
|---------|--------|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture |
| PDF Engine | Apache PDFBox-Android (Apache 2.0) |
| Storage | MediaStore API (Scoped Storage) |

---

## App Flow (3 Steps)

```
[1] Home ? Pick PDF   ?   [2] Draw Signature   ?   [3] Place on Page   ?   [4] Export
```

---

## Project Structure

```
app/src/main/java/com/pdfsigner/app/
?
??? MainActivity.kt                         # Entry point, PDFBox init
?
??? domain/
?   ??? model/
?   ?   ??? PdfDocument.kt                  # Data class for loaded PDF
?   ?   ??? SignaturePlacement.kt           # Placement data (position, size, page)
?   ?   ??? ExportFormat.kt                # Enum: PDF / PNG / JPG
?   ??? service/
?       ??? PdfSignatureService.kt          # PDFBox stamping + rendering logic
?       ??? MediaStoreExporter.kt          # Scoped Storage save logic
?
??? ui/
    ??? viewmodel/
    ?   ??? PdfSignerViewModel.kt           # All state management + export pipeline
    ??? navigation/
    ?   ??? PdfSignerNavHost.kt             # Navigation graph
    ??? screens/
    ?   ??? HomeScreen.kt                   # File picker
    ?   ??? SignatureScreen.kt              # Hosts SignatureDrawingView
    ?   ??? PlacementScreen.kt             # Drag signature on PDF page
    ?   ??? ExportScreen.kt                # Export format selection + result
    ??? components/
    ?   ??? SignatureDrawingView.kt         # Canvas: draw / undo / clear / save
    ?   ??? PdfPageWithSignatureOverlay.kt # PDF page + draggable signature
    ??? theme/
        ??? Theme.kt                        # Material 3 theme
```

---

## Coordinate Mapping – The Critical Detail

The app solves the hardest problem in PDF signing correctly:

```
Screen pixels  ??º renderedPageSizePx???  [0..1] ratio space
[0..1] ratio   ??ª pdfPageSizePt???????  PDF effective points
PDF points     ??flip Y + rotation???????  PDF user-space (bottom-left origin)
```

`PdfSignatureService.toPdfUserSpace()` handles all four rotation cases (0°/90°/180°/270°).

---

## Key Technical Decisions

### Why PDFBox-Android over iText?
- iText 7 Community = AGPL-3.0 ? **forces your app open-source**
- PDFBox-Android = Apache 2.0 ? **free for commercial use**

### Why `LosslessFactory.createFromImage()`?
- Preserves the signature's **alpha channel** (transparent background)
- No JPEG compression artifacts on ink strokes

### Why incremental save (`AppendMode.APPEND`)?
- Preserves the original PDF structure
- Required if the PDF already has digital signatures

### Why native `PdfRenderer` for page count?
- Lightweight – doesn't need PDFBox just to count pages
- Avoids loading the full document into memory twice

---

## How to Open in Android Studio

1. Open Android Studio ? **File ? Open** ? select `PdfSigner/` folder
2. Let Gradle sync complete (will download ~15 MB of dependencies)
3. Run on a device or emulator with **API 26+**

---

## Permissions

| API Level | Permissions Used |
|-----------|-----------------|
| Android 10+ (API 29+) | None required (Scoped Storage / MediaStore) |
| Android 9 (API 28) | `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` |

---

## Possible Next Steps

- [ ] **Resize handle** for the signature overlay (pinch-to-zoom)
- [ ] **Saved signatures** – persist drawn signatures to reuse
- [ ] **Password-protected PDFs** – prompt for password before loading
- [ ] **Multi-signature** – place multiple signatures on different pages
- [ ] **Certificate-based signing** – X.509 + PKCS#12 for legally-binding signatures
