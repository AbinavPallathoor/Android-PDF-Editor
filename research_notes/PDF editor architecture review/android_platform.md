# Android platform and AndroidX PDF

## Can AndroidX replace the current reader on all supported devices?

### Takeaway
No, while preserving minSdk 26 and equal functionality. AndroidX is a credible future viewer, but its recent backport covers rendering, not uniform selection/editing, and its Compose artifact brings a toolchain upgrade.

### Cited Findings
- Checked 2026-10-15: the official release page lists **1.0.0-beta01, released 2026-08-26**, with no stable release. Compose `PdfViewer` and `EditablePdfViewerFragment` remain experimental. Alpha18 backported reading/rendering to **minSdk 28**, including devices below extension13. Alpha17 notes require **compileSdk37 / AGP9.2** when using PDF Compose. These conflict with the supplied project constraints of min26, compile36, AGP8.13. — [AndroidX releases](https://developer.android.com/jetpack/androidx/releases/pdf)
- **Do not equate that backport with text selection support.** The actual `SandboxedPdfDocument.isFeatureSupported` maps `TEXT_SELECTION`, `SEARCH`, `TEXT_EXTRACTION`, and `LINKS` to `areCorePdfApisAvailableInSdk()`. Selection and page-content methods retain extension13 annotations. — [AndroidX document implementation](https://android.googlesource.com/platform/frameworks/support/+/b4dd429cdc82d1fe08c66fe43269a3e8692dcf41/pdf/pdf-document-service/src/main/kotlin/androidx/pdf/SandboxedPdfDocument.kt)
- The SDK helper implements that gate as S-family SDK extension >=13; annotation support uses >=18, form filling >=13, image-object support >=19. The helper checks runtime extension state, not the app's compile/target SDK. — [AndroidX SDK gate refactoring](https://android.googlesource.com/platform/frameworks/support/+/9115c31afbd3696520d894258ab89adac1a5fbc8%5E%21/)
- Source confirms the libraries' minSdk was changed from31 to28. API26/27 remain outside that backport. — [minSdk change](https://android.googlesource.com/platform/frameworks/support/+/088a98fb2f1d7d5ca813d24b42c7b56060867033%5E2..088a98fb2f1d7d5ca813d24b42c7b56060867033/)

### Inferences
- A wholesale AndroidX migration would need a minSdk change or fallback implementation, extension-gated selection/editing, plus separate toolchain evaluation. It does not by itself deliver this user's requirement consistently across min26 devices.
- Prefer a consistent app-owned document/session API and selection model. Keep platform rendering if sufficient, adding a text geometry backend that works on min26; assess PDFBox/PDFium separately. This is an architecture recommendation, not a claim that a particular untested binding already supplies the UI.

### Gaps
- No dependency-resolution/build spike was performed. The Compose warning is an explicit release-note requirement, not a verified AAR compatibility result for every non-Compose artifact. A View/Fragment integration might avoid the Compose-specific upgrade, but still has min28 and runtime feature limitations.
- Source snapshots are official AndroidX development commits and explain the backport; beta01 was not unpacked and diffed against each cited snapshot.

## What selection, layout, zoom, and editing do the APIs actually supply?

### Takeaway
AndroidX supplies a real semantic viewing layer and useful annotation APIs on eligible devices. Its one-page-per-row mode is not the requested snap-through-one-page-at-a-time pager, and annotation support does not remove the app's responsibility for saving and session management.

### Cited Findings
- AndroidX documents long-press text selection with Copy/Select all, pinch/double-tap zoom, search, and scrolling; alpha10 added selection across pages. Alpha13 added pen/highlighter/eraser, undo/redo, forms, and side-by-side layout. Beta01 offers an optional OCR provider; the supplied `pdf-ocr-play-services` implementation uses ML Kit. **Do not add that artifact under this project's no-Play-Services rule.** — [AndroidX releases](https://developer.android.com/jetpack/androidx/releases/pdf)
- `PdfView` supports pinch, quick scale, double-tap back to fit-width, scroll/fling, `zoom`, `minZoom`, `maxZoom`, and navigation to a page or PDF point. Its `pagesPerRow` accepts only1 or2, explicitly meaning pages side-by-side per row. Source has vertical spacing and visible-page ranges: this is a continuous document layout, not an API promising snapping between discrete pages. — [AndroidX PdfView source](https://android.googlesource.com/platform/frameworks/support/+/1abcb4178d48853948b9b566cabff9222d90ab69/pdf/pdf-viewer/src/main/kotlin/androidx/pdf/view/PdfView.kt)
- `EditablePdfViewerFragment` is in `androidx.pdf:pdf-ink`, marked experimental and requiring **S SDK extension18**. It accumulates drafts, exposes `hasUnsavedChanges`, and asynchronously applies drafts. The host receives a write handle and owns persistence, handle cleanup, and exiting edit mode; removing the fragment can terminate an in-progress apply operation. — [Editable fragment reference](https://developer.android.com/reference/androidx/pdf/ink/EditablePdfViewerFragment)
- `PdfWriteHandle.writeTo(ParcelFileDescriptor)` writes PDF contents to a destination; the caller closes the destination. It is not a finished save-original/save-copy user flow. — [Write handle reference](https://developer.android.com/reference/androidx/pdf/PdfWriteHandle)
- AndroidX source files carry Apache2.0 headers. A production adoption still requires checking the selected artifacts' full dependency/license graph; an AndroidX namespace alone does not establish all optional dependencies meet this repo's constraints. — [Official PdfView source license](https://android.googlesource.com/platform/frameworks/support/+/0c67332b6e96feeef07dece681ca0dde844072e8/pdf/pdf-viewer/src/main/kotlin/androidx/pdf/view/PdfView.kt)

### Inferences
- The user's clarified scope (signatures, drawing, text annotations across pages; no rewriting existing text/images) does not demand replacing the whole PDF engine. The likely app work is one multipage document surface, per-page overlays, one shared transform, text hit-testing/selection, reliable view controls, and session-wide dirty/save state.
- Open-with routing, persisted invert preference, a corner dirty icon with save-original/copy, and page overview are app integration/UX concerns. None inherently requires switching the renderer. Library save APIs still serialize document contents; the requested UX can avoid repeatedly opening a destination picker by writing to an authorized original URI when explicitly chosen.
- Keep a discoverable Pages action even if pinch-to-overview is retained. A normal zoom-out gesture should remain a zoom gesture; it should not be the only way into structural edits.

### Gaps
- No documented single-page snapping mode, page reorder/delete/append UI, or per-document invert persistence was found in the examined AndroidX public APIs. Absence from this review is not proof those can never be implemented with customization.
- Built-in image-signature placement/export was not verified. Do not promise it based on pen/highlighter support.
- Scanned image-only PDFs need OCR for text copy; the current scope excludes OCR, so show a clear no-text state rather than promise selection everywhere.

## How do platform API35/36 capabilities differ from AndroidX and backports?

### Takeaway
The existing platform renderer is not inherently a screenshot-to-PDF pipeline: bitmap rendering is a display operation. Newer platform versions expose semantics and editing, but compiling with SDK36 does not make these available to every min26 device.

### Cited Findings
- `PdfRenderer` supports a multipage document through page count and indexed page opening. It renders pages to bitmaps; `write` is **API35** and writes the loaded document state to a writable descriptor (which this platform method closes). — [Platform PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- Platform `PdfRenderer.Page` adds `getTextContents`, `searchText`, `selectContent`, link extraction, and form edits in **API35**. Selection can start/stop at coordinates or text indexes. General annotation/page-object add/update/remove methods are documented as **version36.1**, not merely base API36. — [Platform Page reference](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)
- AndroidX's pre-V adapter uses `android.graphics.pdf.PdfRendererPreV` and is explicitly annotated S SDK extension13; it is a platform extension path, not a bundled semantic engine for arbitrary old devices. — [Official pre-V adapter](https://android.googlesource.com/platform/frameworks/support/+/94635c34944234d3e322a6904421d8c4edc25d8a/pdf/pdf-document-service/src/main/kotlin/androidx/pdf/adapter/PdfDocumentRendererPreVAdapter.kt)

### Inferences
- Rendering into a bitmap does not by itself destroy text, vectors, or other pages in the original PDF. Whether saved edits preserve those depends on the writer. Distinguish a one-page bitmap **editing surface** from saving a raster-only PDF; verify the actual export before making the latter accusation.
- Platform-only semantic selection is a reasonable optional optimization on API35+; it cannot be the sole solution to text copy across the project's full min26 range.
- Current recommendation: **selective reader/editor-session rework, not wholesale AndroidX rewrite**. Preserve the current PDF writer if export validation confirms original content remains intact; add a portable text/selection layer and fix multipage editing/gesture/navigation/save UX. Reconsider AndroidX after a deliberate minimum-device/toolchain decision.

### Gaps
- No real-device/runtime compatibility testing or source PDF/export comparison was performed in this research subtask. These findings are documentation/source verification, not functional acceptance of the current app or a proposed replacement.
