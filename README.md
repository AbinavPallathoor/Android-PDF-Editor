# PDF Editor

A minimal, fully offline PDF reader and editor for Android. The document fills
the screen; tools appear only when you ask for them. Pure-black controls and
normal PDF colours keep the page at the centre.

**[Download the latest APK](https://github.com/AbinavPallathoor/Android-PDF-Editor/releases/latest)**
· Android 8.0+ · No internet permission · Apache-2.0

<img src="docs/screenshots/reading.png" width="260" alt="Full-screen PDF reading"> <img src="docs/screenshots/editing.png" width="260" alt="Black editing tray with a selected text box"> <img src="docs/screenshots/pages.png" width="260" alt="Page overview for reordering and deleting pages">

## Highlights

- **Fill and sign forms fast.** While editing, empty form fields get tap-to-fill
  hints. That includes real form fields, printed boxes and lines, and typed
  blanks like `Name: ______`. Tap to type in place or drop in a fitted signature.
- **Annotate anything.** Pen, highlighter, text and reusable signatures, all
  movable and resizable, with document-wide undo/redo.
- **Rearrange documents.** Reorder or delete pages, append another PDF, or pick
  individual pages from one.
- **Your original is safe.** Edits live in a private draft until you save, and
  saving makes a new file unless you explicitly choose to replace the original.
- **Private by design.** Fully offline. No accounts, ads, analytics, Firebase or
  Google Play Services.

## Features

### Reading

- Scrolling or page-by-page layouts, pinch zoom and pan, and text selection and copying.
- A curved edge scrollbar for fast browsing, showing the page number above its handle.
- Swipe the document title to move between recent PDFs; drafts stay intact.
- Rendered pages are cached on the device, so reopening and switching documents is quick.
- Dark-friendly colour inversion and layout are remembered per document.

### Editing

Tap the pencil in the header to edit. Write mode starts on **Hand**, so your
first touch selects, moves or scrolls rather than leaving a mark.

| Tool | What it does |
| --- | --- |
| Hand | Select and move drawings, text and signatures; drag corner handles to resize text boxes and signatures; tap Fill/Sign hints. |
| Pen / Highlight | Freehand ink and translucent highlights, in your chosen colour. |
| Text | Type directly on the page. Sans, Serif and Mono are bundled; import static TrueType fonts locally. |
| Sign | Draw a signature once, reuse it anywhere; it scales without distortion. |
| Erase | Remove an annotation with a tap. |

Annotations you add stay editable after saving and reopening.

### Fill suggestions

In Hand mode, the app looks for places to write and shows small dashed hints:

- **Form fields:** empty, editable text and signature fields built into the PDF.
- **Printed blanks:** boxes, lines and typed underscore runs next to a
  recognised English label such as *Name*, *Date*, *Email*, *Address*, *Phone*
  or *Signature*.

Tap **Fill** to type in the field, or **Sign** to place your signature fitted
inside it. A hint disappears once something fills its field, and returns if you
undo. Detection is deliberately cautious: scanned pages (no OCR), unlabelled
lines and anything already filled are skipped. Hints add ordinary editable
annotations; they don't change form-field data or create certificate signatures.

### Pages and files

- Page overview to reorder or delete pages; append a whole PDF or selected pages.
- Save as a new file, explicitly replace a writable original, or discard all changes.
- Share the complete annotated PDF with its original text and vectors intact.
- Rename from the document panel; PDFs shared from other apps are kept as
  private imported copies so they stay in Recents.

## Install

1. Download `PDF-Editor-v<version>.apk` from the
   [latest release](https://github.com/AbinavPallathoor/Android-PDF-Editor/releases/latest).
2. Open it on your phone and allow installing from that source when prompted.

Every release is signed with the same key, so new versions install over older
ones and keep your recents, drafts and signatures. Each release lists the APK's
SHA-256 checksum.

## Privacy and storage

The app has no `INTERNET` permission and makes no network calls. Files are
opened through Android's system file picker, with no broad storage permission.

Everything stays on the device:

- **Drafts:** your unsaved edits, kept privately until you save or discard them.
  Closing or switching documents never writes to the original file.
- **Working copies:** private copies of opened PDFs. A copy with no annotations or
  page changes is removed after 10 days unused; the next open re-imports it.
- **Caches:** rendered pages and file fingerprints expire 10 days after last use.
  Android may also clear them early when storage is low, which only costs a
  re-render.

## Limits

- Password-protected PDFs and OCR are not supported. Copying text requires an
  existing text layer.
- Annotations made by other apps are preserved but can't be edited here.
  Android's renderer omits some foreign annotation types (notably ink); this
  app's own annotations are always drawn.
- Some simple PDF viewers may not display editable annotations. Standards-aware
  viewers such as Apple's PDFKit show them.
- Bundled fonts cover Latin, Greek and Cyrillic. Imported fonts must be static
  TrueType (`.ttf`, or `.otf` with TrueType outlines) up to 16 MiB. Variable
  fonts, CFF and font collections aren't supported, and complex-script shaping
  is unvalidated. Characters a font can't draw stop the save with your draft kept.
- Replacing an original depends on the storage provider and can't be guaranteed
  atomic everywhere; drafts and backups are kept if it fails.

## More screenshots

Captured with a synthetic sample document for v0.2.2. Today's header is solid
black, and newer features such as fill hints aren't pictured.

| Inline text | Document controls | Text styling |
| --- | --- | --- |
| <img src="docs/screenshots/inline-text.png" width="240" alt="Typing directly on the PDF with the keyboard"> | <img src="docs/screenshots/document-options.png" width="240" alt="Page, view, share and compress controls"> | <img src="docs/screenshots/text-style.png" width="240" alt="Font selection and local font import"> |
| **Selected-page import** | **Edge scrollbar** | |
| <img src="docs/screenshots/import-pages.png" width="240" alt="Choosing individual pages from another PDF"> | <img src="docs/screenshots/page-scrollbar.png" width="240" alt="Curved edge scrollbar with the current page above its handle"> | |

## Build from source

Requires JDK 21 and Android SDK 36 (`compileSdk`/`targetSdk` 36, `minSdk` 26).

```sh
./gradlew assembleDebug                # debug APK
./gradlew installDebug                 # install on a running device/emulator
./gradlew test ktlintCheck             # JVM tests and lint
./gradlew connectedDebugAndroidTest    # UI tests (device/emulator required)
./gradlew assembleRelease              # optimized, unsigned release APK
```

Tests cover document-wide history, page identity, draft recovery, editable PDF
round-trips, source conflicts, incoming file intents, multipage editing and
saving, field detection, caching and expiry, layouts, and rotated/cropped pages.
Per-release results and coverage limits are recorded in
[docs/validation](docs/validation), most recently
[v0.2.4](docs/validation/2026-10-16-v024.md).

Architecture notes for contributors are in [AGENTS.md](AGENTS.md).

## License

Apache License 2.0; see [LICENSE](LICENSE). Dependency and font licenses are in
[THIRD_PARTY_LICENSES](THIRD_PARTY_LICENSES). Every dependency is
Apache-2.0, MIT or BSD licensed.
