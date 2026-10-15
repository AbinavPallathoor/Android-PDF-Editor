# Multipage PDF annotation editor implementation plan

**Goal:** Keep the entire PDF navigable while adding annotations and signatures, with reliable selection, layouts, zoom and saving.

**Architecture:** One durable document session owns ordered page identities, PDF-space annotations, undo/redo and saved state. Compose renders the same page surfaces in continuous and paged layouts. PDFBox reads/writes editable annotations and supplies text geometry; platform PdfRenderer remains the bitmap backend.

**Approved interaction brief:** the user's in-chat iPad-style editing requirements and explicit instruction to implement and validate everything. Execute in this checkout on `feature/multipage-editor`, preserving existing changes (backed up under `/tmp/pdf-editor-before-multipage-20261015`).

## Constraints

- minSdk 26, compile/target SDK 36; existing AGP/Kotlin toolchain.
- Fully offline, no INTERNET permission or new unreviewed dependencies.
- Normal PDF colours by default; remember inversion and layout choices.
- Original file changes only after explicit Save to current file. Save as new remains default.
- No original text/image content rewriting, OCR, accounts or cloud services.

## Tasks and verification

1. **PDF annotation and text backend** — `domain/Annotation.kt`, new `data/pdf/PdfBoxDocumentContent.kt`, backend JVM tests. First reproduce missing multipage editable round-trip. Write/read Ink, FreeText, highlights and vector signatures, preserve foreign annotations, avoid duplicate saves, expose PDF-space text bounds. Validate mixed pages, rotations/crop boxes, original text and page counts.
2. **Android opening** — `MainActivity.kt`, new `OpenDocumentRequest.kt`, intent tests. First reproduce ignored VIEW intent. Queue cold/warm open requests with actual permissions and route once into the shared opening flow. Persist only offered grants; ephemeral files must not create broken recents.
3. **Document model** — new `domain/DocumentEdits.kt`, session tests, `ReaderViewModel.kt`. First test all-page dirty/undo state, reorder identity and deletion. Persist drafts atomically; serialize operations; write new revisions without mutating renderer-backed files. Snapshot all annotations for save/share and clear only the saved version.
4. **Shared document UI** — `ReaderScreen.kt`, new page/toolbar/gesture components. Remove isolated writing-screen entry. Provide persistent tools, text/highlight/draw/sign/erase, undo/redo, continuous/paged layout, fit-page zoom, pan, long-press selection and Copy, explicit Pages action, corner save menu. Reuse saved-signature repository. Test multipage annotations and navigation through real Compose pointer input.
5. **Save integration** — `NavGraph.kt`, file destination tests. Export one immutable complete snapshot for share and both save targets. Handle cancellation, unavailable write grants, failed streams and picker recreation visibly; retain recoverable drafts. Never silently overwrite originals.
6. **Validation and documentation** — fresh JVM suite, ktlint, debug/release builds, release manifest and size check; boot emulator and run Compose instrumentation plus cold/warm VIEW, SAF save-copy/current, reopen and visual checks. Update AGENTS.md and README to describe final behaviour and actual limitations.

## Review focus

- Annotations must stay with their original pages after reorder/delete/append.
- Repeated saving must not duplicate annotations or clear edits made during save.
- Crop/rotation and page aspect ratio must agree between overlays, text and saved PDF.
- Read-only or temporary VIEW grants must open safely and never imply write capability.
- Two-finger navigation must cancel a pending ink stroke; long press must not open a scrubber.

Each substantive task follows a failing regression test, implementation, and passing verification. Parallel work is restricted to the independent PDF backend and Android opening files; integration and validation run sequentially.
