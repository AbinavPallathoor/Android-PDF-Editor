# Android PDF editing library decision

## Does multipage annotation editing require replacing the PDF engine?

### Takeaway

No full app or engine rewrite is justified for the clarified scope: drawing, added text, and visual signatures across every page. Keep native Compose and the existing PDFBox writer; rebuild the document session and page surfaces, then prove standard annotation round-tripping before deciding on a renderer replacement. This is an architectural recommendation, not a claim that compatibility has been tested.

### Cited Findings

- The project currently pins `com.tom-roush:pdfbox-android:2.0.27.0` in `gradle/libs.versions.toml`. Upstream documents an Apache-2.0 Android port based on PDFBox 2.0.27, requiring API 19 for full functionality; API 26 is within that documented range. — [Upstream README](https://github.com/TomRoush/PdfBox-Android)
- The exact `v2.0.27.0` source exposes `FreeText` and `Ink` annotation subtypes, ink path getters/setters, and annotation appearance generation via `PDFreeTextAppearanceHandler` and `PDInkAppearanceHandler`. These are actual PDF annotation objects, separately represented from page content. — [Pinned PDAnnotationMarkup source](https://raw.githubusercontent.com/TomRoush/PdfBox-Android/v2.0.27.0/library/src/main/java/com/tom_roush/pdfbox/pdmodel/interactive/annotation/PDAnnotationMarkup.java)
- A stamp annotation model also exists in the port. A custom appearance can be investigated for a transparent signature image rather than treating the whole page as an image. — [PDAnnotationRubberStamp source](https://raw.githubusercontent.com/TomRoush/PdfBox-Android/master/library/src/main/java/com/tom_roush/pdfbox/pdmodel/interactive/annotation/PDAnnotationRubberStamp.java)
- PDFBox is a low-level library and supplies no automatic paragraph layout or line wrapping. Unicode characters outside WinAnsi require an appropriate embedded font through `PDType0Font`; plain Helvetica is insufficient for general Unicode. — [Apache PDFBox FAQ](https://pdfbox.apache.org/2.0/faq.html)
- PDFium core has lower-level page object mutation APIs including text object replacement, but this does not imply an Android binding exposes those operations or supplies an editor UI. — [Official PDFium edit header](https://pdfium.googlesource.com/pdfium/+/main/public/fpdf_edit.h)

### Inferences

- Store editable app objects per stable page identity in a document-wide session. A selected page index is navigation state, not the annotation store. Reorder/delete/append must update identity-to-index mapping without moving marks to the wrong page.
- Preferred save format: PDF `Ink` for strokes, `FreeText` with generated appearances for added text, and a `Stamp` plus custom appearance for a signature image, subject to a round-trip spike. Preserve unrelated existing annotations. Give app-created objects stable IDs so reopening can recover and edit them. Keep flattening as a deliberate export option rather than the sole persistence path.
- A signature picture is a visual signature, not a certificate-based digital signature. No cryptographic signing feature is needed for this request.
- A bitmap preview is normal in a PDF viewer. The defect is treating one page bitmap as the editing surface with no shared multipage model; it does not establish that the exported document is fully rasterized. The parent code audit found original PDF content is retained and annotation content is appended.
- Changing existing source text/images is unnecessary after the user's clarification. Do not spend effort on font reconstruction, text reflow, content-stream rewriting, or custom PDFium editing JNI.

### Gaps

- Standard annotation support is verified in source, not in a runtime prototype. Prove create → save → reopen → edit/delete for each app-created type in this app and a second independent reader.
- Check whether platform `PdfRenderer` consistently displays those saved annotation appearance streams across API 26–36. If it does not, render annotations in the app's overlay consistently or replace the renderer only after comparison testing. Avoid rendering both the saved annotation and its overlay simultaneously.
- Validate transparent image stamp appearance, FreeText resource/font embedding, and rotation/crop behavior. No claim that stock appearance generation handles every Unicode script or bidirectional layout correctly.

## How should text selection and reliable rendering work on API 26?

### Takeaway

Start with a text extraction/selection spike using PDFBox already present in the APK. Introduce a renderer/text backend boundary so a current PDFium binding can replace extraction and rendering if the spike fails quality or latency targets; do not adopt the repo's old PDFium fallback blindly.

### Cited Findings

- Platform `PdfRenderer.Page.getTextContents()` and `selectContent()` were added in API 35. They cannot by themselves supply text selection to all API 26+ devices. — [Android Page API](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer.Page)
- PDFBox-Android `PDFTextStripper` offers `writeString(text, textPositions)` and `processTextPosition` hooks. `TextPosition` carries text, dimensions, effective text rendering matrix, and direction-adjusted coordinates; its documentation warns that glyph count and Unicode string length can differ for ligatures. — [PDFTextStripper source](https://raw.githubusercontent.com/TomRoush/PdfBox-Android/master/library/src/main/java/com/tom_roush/pdfbox/text/PDFTextStripper.java), [TextPosition source](https://raw.githubusercontent.com/TomRoush/PdfBox-Android/master/library/src/main/java/com/tom_roush/pdfbox/text/TextPosition.java)
- `io.legere:pdfiumandroid:2.0.3` is the README's current coordinate. The changelog records 2.0.3 on 2026-07-26 and 2.0.1/2.0.2 fixes for annotations, native memory, and rendering background. Its README says it supplies Android bindings rather than UI. — [PdfiumAndroidKt README](https://github.com/johngray1965/PdfiumAndroidKt), [Changelog](https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/main/CHANGELOG.md)
- That binding exposes text retrieval, character boxes, position-to-character hit testing, range rectangles, bounded text, and search. Its page API includes coordinate conversion with rotation and an explicit `renderAnnot` flag (default false). — [PdfTextPage source](https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/main/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfTextPage.kt), [PdfPage source](https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/main/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfPage.kt)
- The PdfiumAndroidKt README headline says API 21+, but current module source sets `minSdk = 24`. Both cover this project's API 26, but pin and verify the actual release artifact instead of relying on the headline. — [Module build](https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/main/pdfiumandroid/build.gradle.kts)
- The binding has `saveAsCopy`, but inspected page/document APIs do not demonstrate creation/mutation of Ink, FreeText, and Stamp annotations. It should be evaluated as a rendering/text backend, not presented as a complete editing replacement. — [PdfDocument source](https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/main/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfDocument.kt), [PdfPage source](https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/main/pdfiumandroid/src/main/java/io/legere/pdfiumandroid/PdfPage.kt)
- The documented `mhiew/pdfium-android:1.9.2` fallback's README describes its core update as PDFium `7.1.2_r36`, with compile SDK 31 notes. That is evidence of an old documented baseline, not proof of suitability for a target-36 app. — [mhiew README](https://raw.githubusercontent.com/mhiew/PdfiumAndroid/master/README.md)
- Text extraction cannot recover characters from image-only scans without OCR; PDF text may be drawn out of reading order or use unusable encoding. — [Apache PDFBox FAQ](https://pdfbox.apache.org/2.0/faq.html)

### Inferences

- Add a `PdfTextSource` interface independent of Compose and a page model containing Unicode ranges and PDF-space bounds. Run extraction off the UI thread, cache a bounded set of visible/nearby pages, and use the same crop/rotation transform for page rendering, selection hit tests, and annotations.
- Long press should enter text selection; scrub navigation needs an explicit control. Implement handles, multi-line selection, Copy, and clear selection on page/document changes as deliberate UI behavior. None comes automatically from a PDFium bitmap renderer.
- If PDFBox extraction works accurately and quickly enough, keeping it avoids an extra native dependency. If it does not, a modern PDFium renderer plus text layer is a selective replacement behind interfaces. It does not fix bad gesture arbitration, missing Android intents, preference persistence, or save UI by itself.

### Gaps

- Compare PDFBox and PDFium extraction on Latin ligatures, combining marks, CJK, Arabic/RTL, mathematical text, and malformed/missing ToUnicode mappings. Track reading order, copied string correctness, and coordinate agreement; one UTF-16 `Char` is not necessarily one PDF glyph or Unicode code point.
- Verify all four page rotations, offset CropBoxes, mixed page sizes, zoomed pages, and normalized reading position. A coordinate API does not guarantee our overlay transform matches it.
- For PDFium adoption, test actual published AAR on API 26 and 36, arm64 and emulator architecture, 16 KB memory-page compatibility, APK size, cancellation/closing safety, and repeated long-document rendering. Current-main source and README alone do not prove artifact compatibility.

## What licensing, maintenance, and save constraints affect the decision?

### Takeaway

PDFBox remains a reasonable first implementation path but its Android port needs an explicit maintenance assessment. PDFium is the strongest researched permissive native fallback for render/selection; MuPDF, iText, and proprietary SDKs do not satisfy the project's allowed-license rule. A small dirty-state save menu is a workflow change and does not require incremental PDF writes.

### Cited Findings

- PDFBox-Android's release page still identifies `v2.0.27.0` as Latest and the README still targets PDFBox 2.0.27. This establishes an old upstream API baseline, not that the port is definitively abandoned. API inspection through GitHub's unauthenticated REST API was rate limited, so latest commit dates were not verified. — [Official releases](https://github.com/TomRoush/PdfBox-Android/releases), [README](https://github.com/TomRoush/PdfBox-Android)
- The pinned build declares Bouncy Castle `bcprov`, `bcpkix`, and `bcutil` 1.72 and an optional JPEG2000 dependency (confirm artifact resolution before release). These transitive components require their own version/license review; a top-level Apache license is insufficient. — [Pinned build](https://raw.githubusercontent.com/TomRoush/PdfBox-Android/v2.0.27.0/library/build.gradle)
- Apache's security table lists newer PDFBox 2.x fixes, but the two listed 2026 path traversal advisories affect the `ExtractEmbeddedFiles` examples module, not core. Do not misreport them as demonstrated vulnerabilities in this app's annotation path. — [Apache security page](https://pdfbox.apache.org/security.html)
- PdfiumAndroidKt's license combines Apache-2.0 wrapper terms and PDFium's BSD-style terms; PDFium also contains a `third_party` tree. The entire shipped native build needs license/notice inventory before approval, including optional codecs and fonts. — [Binding license](https://raw.githubusercontent.com/johngray1965/PdfiumAndroidKt/main/LICENSE), [PDFium third_party](https://pdfium.googlesource.com/pdfium/+/main/third_party/)
- MuPDF is available under AGPL or a commercial license. iText 5 and later use AGPL/commercial dual licensing. Both are excluded by this project's Apache/MIT/BSD-only rule unless the user changes it. — [MuPDF license](https://mupdf.readthedocs.io/en/latest/license.html), [iText licensing](https://itextpdf.com/how-buy/AGPLv3-license)
- Mozilla PDF.js is Apache-2.0 and supplies an HTML5 PDF viewer, with modern/legacy JavaScript builds. It is a possible web viewer alternative rather than a drop-in native Compose PDF engine. — [Mozilla repository](https://github.com/mozilla/pdf.js)
- PDFBox offers incremental saving, but its source warns that output must never point at the source file, and documents requirements for update marking. A save-to-current workflow cannot simply call `saveIncremental` on the same input/output URI. — [PDDocument source](https://raw.githubusercontent.com/TomRoush/PdfBox-Android/master/library/src/main/java/com/tom_roush/pdfbox/pdmodel/PDDocument.java)

### Inferences

- Prefer: keep Compose, PDFBox structural editing and annotation serialization; rebuild multipage session and gesture UI; add text extraction; defer renderer swap until corpus tests show a need. PDF.js would add WebView, JavaScript bridge, local resource packaging, and gesture integration costs; those costs are not justified by annotation-only requirements today.
- Save menu design: show a corner dirty icon after any annotation/structural edit; tapping offers explicit Save to current file and Save as new file. Generate and validate a complete temporary PDF first, then commit to the authorized destination. A complete PDF result is normal even when only one annotation changed; incremental updates are a separate optimization.
- Preserve dirty state until the destination write succeeds. Repeated saves must replace/update app-owned annotations, not append duplicate copies. Open-with may provide read permission without write permission; availability of Save to current depends on the actual URI/provider permissions. Parent researcher should verify Android provider semantics.
- Saving should merge edits across all pages into the latest structural working copy, never restart from an obsolete original after reorder/delete/append. Rebuild the renderer after committing a new revision and invalidate text, thumbnail, and overlay caches together.
- Bundle all required fonts/codecs locally, with approved licenses and notices. Avoid automatically bundling SIL OFL fonts when the project's rule permits only Apache/MIT/BSD; resolve that policy deliberately if broader font support needs them.

### Gaps

- No candidate native binary has passed a full transitive license audit here. No library has been added or upgraded. Update `THIRD_PARTY_LICENSES` only with the exact selected/resolved artifacts.
- Decide whether to maintain/backport the existing Android port or adopt a current permissive fork after an actual maintenance comparison. A newer desktop Apache PDFBox release cannot be assumed Android-compatible without porting work.
- Neither read-only library research nor source inspection proves successful save recovery under provider failures, storage exhaustion, process death, or concurrent external edits. These need targeted runtime acceptance tests before enabling replacement of originals.
