# PDF Studio

Sign and edit PDF files on a phone and on a computer.

- `PdfSigner/app`, `PdfSigner/shared`, `PdfSigner/desktopApp`: the Android app (Kotlin, Jetpack Compose, Material 3,
  MVVM) and its shared code, with [PDFBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) as the PDF
  engine. See `PdfSigner/README.md`.
- `PdfSigner/electronApp`: "PDF Studio Desktop", the same idea as an Electron app.

## Build

```bash
# Android (Android Studio, or):
cd PdfSigner && ./gradlew assembleDebug

# Desktop
cd PdfSigner/electronApp && npm install && npm start
```

The desktop app's cloud features use Firebase. Put your own project's settings in
`PdfSigner/electronApp/server/cloudService.js`, where the placeholders are. No key is included.

## Status

Published from the author's archive as is. It is a working project, not a maintained product.

## Licence

MIT (see `LICENSE`).
