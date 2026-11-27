# IntactFolio — Project Agent Instructions

## Overview
IntactFolio is a 100% offline, ad-free, privacy-first Android document viewer and layout/structure-preserving editor for **PDF**, **Word (`.docx`)**, and **Excel (`.xlsx`)**.
`android.permission.INTERNET` is intentionally excluded from `AndroidManifest.xml`.

## Tech Stack
- **Language / UI:** Kotlin, Jetpack Compose (Material 3, `compose-bom:2024.09.00`)
- **Android SDK:** `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`, Java 17
- **PDF Engine:** `com.tom-roush:pdfbox-android:2.0.27.0` + Android `PdfRenderer`
- **Word / Excel Engines:** Pure Kotlin OOXML (`java.util.zip` + `javax.xml` DOM), zero extra runtime dependencies

## Architecture
- `app/src/main/java/dev/rasne/intactfolio/model/`
  - `PdfModels.kt`: `PdfTextBlock`, `TextEditOperation`, `PdfLoadState`, `ViewerMode`, `AlignmentGuide`
  - `DocumentModels.kt`: `DocumentType`, `WordElement` (`WordParagraph`, `WordTable`), `WordPageSetup`, `WordPage`, `WordDocumentData`, `ExcelCell`, `ExcelSheet`, `ExcelWorkbookData`
- `app/src/main/java/dev/rasne/intactfolio/core/`
  - `PdfEngine.kt`, `PdfTextLocator.kt`, `PageGeometry.kt`: PDF rendering, text block extraction, and live PDF editing
  - `DocxEngine.kt`: `.docx` parsing, Print Layout pagination, and structure-preserving XML mutation
  - `XlsxEngine.kt`: `.xlsx` parsing and structure-preserving sheet XML mutation (cells, formulas, shared strings, rows, columns)
- `app/src/main/java/dev/rasne/intactfolio/ui/`
  - `PdfViewModel.kt`: Unified document state, 3-tier type detection, undo snapshots, SAF save/share
  - `PdfViewerScreen.kt`: Unified Compose scaffold, home screen, and document-type switcher
  - `components/`: `PdfPageView.kt`, `TextEditDialog.kt`, `AddTextDialog.kt`, `WordDocumentView.kt`, `ExcelWorkbookView.kt`

## Verification Commands
- Unit tests & debug APK build:
  ```powershell
  .\gradlew.bat testDebugUnitTest assembleDebug
  ```

## Conventions
- `applicationId = "dev.rasne.intactfolio"` and `app_name` is `IntactFolio`.
- User-facing strings and status messages are in Turkish.
- Never add `INTERNET` permission or external network calls.
- Close `ZipFile` handles before replacing files on disk.
