# AGENTS.md

Guidance for working on this repo. Keep this file up to date as the project evolves.

## What this is

A native Android PDF reader and multipage annotation editor with visible tools,
black application chrome and normal PDF colours by default. 100% FOSS, fully
offline (no `INTERNET` permission, ever), F-Droid publishable. The current editor
section supersedes the original gesture-only milestone design.

## Current editor (October 2026)

The user replaced the original single-page writing-mode requirement with a shared,
iPad-style multipage annotation surface. `ReaderScreen` now uses the same
`DocumentPageSurface` in scrolling and paged layouts. `DocumentTitleBar` and its thumbnail expansion are reused.
The reader is full-screen: at the start of the document the header stays visible
and the PDF sits below its controls. Away from the start, chrome fades after
idle and a Hand tap reveals it. The header uses a compact 64dp control area below the status/camera inset.
The status/camera inset and compact controls are solid black; there is no gradient
or white paper backdrop behind the header.
Header swipes follow the finger and settle with a damped spring. Neighbour
thumbnails preload off the UI thread; navigation uses the exact previewed URI
and flushes the draft. A temporary display frame bridges loading until the next
document's first page renders, then fades out. It never changes PDF content.
Document options morph to a larger preview above a bottom-anchored black icon
deck. Page/view and file actions have separate rows. Tap the filename to rename
inline; Done confirms, Back or dismissal discards unfinished text. Pages enters
with a fade/slide/scale transition. A curved right-edge handle replaces the
bottom page counter during scrolling; its label sits above the draggable thumb
and follows the mostly visible page. Stored reading position still refers to the
first visible page and its normalized offset.
Chrome and panels use pure black. The pencil enables a compact monochrome editing tray; colours are requested
from its palette button. Done returns to reading. Hand scrolls/selects text and,
only during editing, drags app-created ink/text/signatures. Single-finger Hand
drags pan both axes when zoomed; page tools and selection claim gestures first. Two fingers navigate
in all tools. Pages, layout, and fit live in the expanded document panel. Long press selects PDF text, never the old scrub panel. Normal PDF
colours are the default; optional inversion and layout persist per URI. The
application chrome remains black.

`DocumentEdits` owns stable page IDs, virtual page order, per-page annotations,
whole-document undo/redo and exact saved-snapshot dirty state. `DocumentDraftStore`
atomically persists the immutable base filename, current/saved snapshots and
source fingerprint. Rendering uses a private immutable base with app-owned
annotations removed; overlays draw those models once. PDFBox always reads a
separate snapshot, not the file held open by PdfRenderer. Reorder/delete are
virtual until export; append creates a fresh immutable base and one history entry.
The Add pages tile opens SAF, then a source thumbnail picker for all pages or a
chosen subset in original order. Selection survives recreation; VM-owned import
status prevents duplicate additions and retains selection on failure. Preview
uses a separate file with cancellation-safe renderer ownership.

`PdfBoxDocumentContent` writes standard Ink, FreeText and Highlight annotations
with appearance streams and serialized app models, preserving foreign annotations
and original PDF content. Roboto is bundled with its Apache-2.0 license. Editable
coordinates use the displayed CropBox after rotation, with a top-left origin.
`AnnotationBounds` keeps movable text/signatures within the page. Text extraction
uses PDFBox geometry and runs off the UI thread.

Text is entered directly on the page through `InlinePageText`, with IME-aware caret visibility. Live updates persist through `DocumentEdits.editText`, coalesced by typing session into one undo step. Visual wrapping becomes explicit PDF newlines; multiline text stays within the page. Selection outlines use PDF-space bounds. Selected text boxes expose four corner
handles after typing finishes; resizing reflows preserved source text at a stable
font size and commits one undo step. Width/height and font IDs are PDF-space model
properties and survive drafts/export. The compact tray offers contextual text
colour and font controls. Sans (Roboto), Serif (Droid Serif), and Mono (Droid Sans
Mono) are bundled under Apache-2.0; private static TrueType imports use SAF.
`FontRepository` validates bounded files off the UI thread. Custom fonts are fully
embedded and can be recovered from PDF appearances; missing fonts are reported
and text reflow waits for import or an explicit replacement font. Typeface/style
objects must stay stable during inline layout to avoid recomposition loops.
The tray animates its content size; resizing tracks the finger without tween lag.

The bottom-right save icon offers save-as-new (default), explicit current-file
replacement when writable, and Discard all changes. Discard restores the saved
snapshot including page structure, clears both histories and persists that state.
Private draft persistence never writes a PDF source; all objects remain editable. Save/share snapshot every page. Pending export JSON
stays on disk; only paths enter saved-instance state. Replacement checks the
original fingerprint, retains a unique backup and verifies output bytes. Dirty
state clears only for the exact exported snapshot. External changes refresh
clean drafts; dirty conflicts keep the draft and disable replacement. URI-grant
flags and cold/warm VIEW/SEND requests flow through `OpenDocumentRequest`.

`PageRenderCache` keeps lossless rendered pages in `cacheDir/rendered_pages`,
keyed by session, immutable base filename, page and pixel size, so reopening or
switching documents skips re-rendering. Entries expire 10 days after last use
(256 MiB cap, pruned at app start). `SourceFingerprintCache` reuses the original's
SHA-256 only while its reported size and modification time are unchanged and the
stamp was used within 10 days; missing metadata always re-hashes.
`WorkingCopyExpiry` (app start) deletes `working_copies/<session>` folders unused
for 10 days only when the draft is clean, has no annotations, and keeps exactly
the imported pages in order (`DocumentDraft.importedPageCount`; legacy drafts
without it are kept until reopened). Readers claim their session under the same
`workingCopySessions` lock first, as does legacy draft migration.

`PdfBoxFillTargetDetector` reads a separate immutable snapshot off the UI thread.
It detects eligible empty text/signature widgets plus conservative English-labelled
printed boxes/lines, accounting for crop and rotation. Filled/protected/hidden
widgets and occupied graphics are excluded; explicit clipping suppresses printed
guesses for the page. There is no OCR. `PdfFillTargetSource` copies each
renderer base once and keeps one PDFBox document open for every page lookup;
`ReaderViewModel` holds its operation lock only for that snapshot, never during
detection, and closes the source whenever the base changes. Acquired copies and
documents are stored on the owner inside NonCancellable blocks; never return an
acquired resource into a canceled caller. Close may race an in-flight lookup;
whichever finishes last releases.
Hand shows contextual Fill/Sign hints only while editing. Text opens inline at
the field bounds; signatures fit/center the target, including a deferred capture
when no saved signature exists. Hints hide under occupied app annotations and
return after removal/discard/undo. Placement creates ordinary editable annotations,
not AcroForm value changes or cryptographic signatures.

Android PdfRenderer does not render every annotation type (Ink is omitted even
on API 35). Own overlays cover app-created annotations; API 35 text/highlight
flags help foreign annotations. Foreign annotations remain preserved, but editing
them and displaying all types are not supported. Do not claim OCR, arbitrary
Unicode shaping, physical-device validation or API 26 runtime coverage from an
API 35 emulator pass. Keep README limits and validation evidence accurate.

The milestone testing notes below are historical; the current design above
supersedes their single-page writing, gesture-only entry, and flatten-only save
behaviour. Legacy WritingScreen/gesture helpers remain for compatibility but are
not reached by document navigation.

## Hard constraints (do not relax these without the user's explicit sign-off)

- No `INTERNET` permission, no network calls, no analytics/crash reporting, no
  Google Play Services/Firebase, no accounts, no ads, no paid features.
- Every dependency must be Apache-2.0, MIT, or BSD licensed. No AGPL/GPL
  (this rules out MuPDF and iText). Update `THIRD_PARTY_LICENSES` whenever a
  dependency is added, removed, or upgraded.
- The app itself is Apache-2.0 (see `LICENSE`).
- Never overwrite a user's original PDF silently — "save as copy" is the
  default; "replace original" is an explicit, separate opt-in action.
- Application chrome stays dark with a true black (`#000000`) background. PDF
  content is normal by default with optional remembered inversion, as requested
  by the user. No dynamic color or `isSystemInDarkTheme()` branching.

## Stack

- Kotlin, single-activity Jetpack Compose UI (Material3 used only as a
  structural base, heavily restyled), Navigation Compose with type-safe
  (`kotlinx.serialization`) routes.
- Min SDK 26, compile/target SDK 36.
- No DI framework (Hilt/Dagger) — a hand-rolled container in
  `PdfEditorApplication` is enough at this scale; revisit only if the graph
  grows meaningfully more complex.
- Storage Access Framework (`ACTION_OPEN_DOCUMENT`, persisted URI
  permissions) for opening files. No broad storage permissions.
- Room for the recents list.
- PDF rendering (reading mode): platform `android.graphics.pdf.PdfRenderer`,
  wrapped behind the `PdfDocumentRenderer` domain interface. A PDFium binding
  (`mhiew/pdfium-android`, Apache-2.0 over PDFium's BSD-3-Clause core) is the
  documented fallback if real-world testing shows `PdfRenderer` is
  inadequate — swap the implementation behind the interface, don't touch UI.
- PDF writing/editing: `com.tom-roush:pdfbox-android` (Apache-2.0). Structural
  operations sit behind `PdfEditor`; `PdfBoxDocumentContent` owns editable
  annotation round-trips and text extraction. Compression has its own interface.
- Gradle version catalog (`gradle/libs.versions.toml`) for all dependency
  versions — don't hardcode versions in build files.
- ktlint via the `org.jlleitschuh.gradle.ktlint` Gradle plugin.

### Toolchain version notes (why these versions, so future upgrades don't regress silently)

- **AGP 8.13 / Gradle 8.13**, not AGP 9.x: AGP 9 is a recent major version
  with breaking DSL changes (namespace/minSdk block syntax, bundled Kotlin
  support) that we deliberately avoided for build-iteration stability. AGP
  8.13 fully supports `compileSdk = 36`. Revisit AGP 9 once it's had more
  time to mature and this project's toolchain needs a reason to move.
- **Kotlin 2.3.21, not 2.4.x**: KSP (needed for Room's annotation processing)
  had no published release targeting the Kotlin 2.4 compiler plugin API at
  the time this was set up — only Kotlin 2.3.x. Don't bump Kotlin past what
  KSP's latest published version actually supports; check
  `com.google.devtools.ksp` release notes before upgrading either one.
- **Room 2.8.x, not Room 3.0**: Room 3.0 (released March 2026) is a from-
  scratch KMP-first rewrite (KSP-only, coroutines-only DAOs, new
  `androidx.sqlite` driver backend) with a real migration path from 2.x, not
  a drop-in bump. Room 2.x is in maintenance mode but stable and sufficient
  for the recents list. Migrate deliberately in its own milestone if ever
  needed, not as a side effect of a routine dependency bump.

## Structure

Single Gradle module (`:app`), package-separated:

```
com.pdfeditor.app/
  ui/          # Compose screens, gesture controllers, animations, theme
  domain/      # PdfDocumentRenderer, PdfEditor, RecentsRepository interfaces; models
  data/
    pdf/       # PdfRendererEngine, PdfBoxEditor, PdfCompressor, DocumentSession (Milestone 2+)
    db/        # Room: RecentDocumentEntity/Dao/Database, RoomRecentsRepository
    cache/     # thumbnail cache, sidecar annotation storage (Milestone 2+)
```

Multi-module Gradle was considered and rejected for now — this is a solo v1
project and package boundaries already keep the PDF layer (`domain` +
`data/pdf`) cleanly separable from UI, which is what actually matters for
adding form-filling/encryption support later without a rewrite.

## Key design decisions to preserve

- **Annotation coordinates are stored in PDF user-space points, never screen
  pixels** (`DrawStroke`, `TextBox`, `SignaturePlacement`), via one
  `PdfPageTransform` used both for the Compose overlay draw and for
  flattening into `PDPageContentStream` at save time. Get this wrong and
  annotations land in the wrong place after save.
- **Reading position is stored as a normalized fraction, not raw pixels**
  (`lastScrollFraction: Float` in `[0,1]` within `lastPageIndex`), because raw
  pixel offsets break across rotation and zoom changes.
- **`PdfRenderer`/`Page` are not thread-safe and only one page can be open at
  a time per renderer instance.** `PdfRendererEngine` must serialize all
  render calls through a single dedicated dispatcher + mutex, and cancel
  queued (not in-flight — `render()` can't be interrupted) requests for pages
  that scroll out of view. Milestone 2 relies on `LazyColumn`'s own
  virtualization + per-item `LaunchedEffect` cancellation for this rather than
  a hand-rolled priority queue — revisit only if profiling shows it's not
  enough.
- **`Page.render()` requires `Bitmap.Config.ARGB_8888` — `RGB_565` throws
  "Unsupported pixel format"**, confirmed live (silently caught, it just
  produces a blank page with no error, which is what actually happened during
  Milestone 2 testing). The `RGB_565` memory saving from the original design
  pass isn't available through this API; budget bitmap cache size accordingly.
  Also: **`render()` does not paint a white page background** — it only draws
  actual content — so the destination bitmap must be `eraseColor(WHITE)`'d
  before every render call, or unpainted areas show through as whatever was
  already in the bitmap (this is what made rendered text/shapes invisible
  before the fix: black content on an un-erased, effectively black bitmap).
- **PdfRenderer and PdfBox-Android never hold the same document open at the
  same time.** Reading mode holds a read-only PFD via `PdfRendererEngine`;
  entering Writing mode closes it and copies the source into a private
  working file (`filesDir/working_copies/<docId>.pdf`) for PdfBox-Android,
  which wants file-based random access, not a streaming `content://` fd.
- **Gesture arbitration lives in one `awaitEachGesture` block per surface**,
  not stacked independent `detectTapGestures`/`detectTransformGestures`
  modifiers (they race each other). Confirmed live in Milestone 2: layering
  `detectTapGestures` next to a separate custom pinch `awaitEachGesture` loop
  on the same `pointerInput` block sporadically dropped the second tap of a
  double-tap — two coroutines independently calling `awaitPointerEvent()` on
  the same block can each miss events during fast sequences. Fixed by
  merging into one state machine (`ui/reader/PinchZoomGesture.kt`) that
  manually distinguishes tap/double-tap/pinch/pan itself. Apply the same
  "one reader of the event stream" rule when building the full gesture engine
  in Milestone 3. Classification logic should stay extractable into a
  plain-Kotlin state machine over an abstracted `TouchSample` so it's
  unit-testable without Compose.
- **The system status bar and navigation bar intercept touches in their own inset region for
  their own gestures (pull-down notification shade, swipe-up-home) before any app ever sees
  them.** Confirmed live: an edge-swipe zone placed at literal y=0 (or the literal bottom edge)
  silently receives nothing, no matter how the gesture code is written - it looks exactly like a
  Compose bug but isn't one. Query `WindowInsets.statusBars`/`WindowInsets.navigationBars` and
  start edge-swipe-to-trigger zones just past those insets (see `ReadyReaderContent` in
  `ReaderScreen.kt`), never at the literal screen edge.
- **A plain `pointerInput`/`awaitPointerEvent()` loop cannot reliably measure a drag that a
  `Modifier.scrollable` (e.g. `LazyColumn`) also recognizes as a scroll.** Once the scrollable's
  own internal gesture detector confirms the drag past touch slop, Compose cancels *other*
  pointer-input consumers watching that same pointer outright - the pointer disappears from later
  events entirely, not just a reduced/consumed delta. This is true whether the other consumer is a
  sibling overlay `Box` or the direct parent wrapping the scrollable. Confirmed live twice while
  building edge-swipe-to-close: first with a separate overlay zone (measured a few px before losing
  the pointer - looked like a bounds issue, wasn't), then again after folding the same detection
  into the content's own unified gesture loop (still lost the pointer around the same real
  distance, proving it wasn't about node bounds or a second coroutine at all). **The fix: use
  `NestedScrollConnection.onPreScroll`**, which observes every scroll attempt cooperatively
  (pre- or post-consumption) without racing or being cancelled by the scrollable - see
  `ReadingPager`'s `edgeSwipeConnection` in `ReaderScreen.kt`. Gate such gestures on
  `canScrollBackward`/`canScrollForward` (i.e. "nothing left to scroll that way") rather than
  "touch started near the screen edge" - it sidesteps needing `WindowInsets` math entirely and
  only engages when pulling further would otherwise be a no-op anyway.
- **Dark-friendly invert (Milestone 2) is a full RGB color-matrix invert, not
  a true "smart" invert that preserves photos/images.** True per-content
  smart invert (à la iOS) needs to distinguish image regions from
  text/vector content, which isn't available from `PdfRenderer`'s flat
  rasterized bitmap output. Documented simplification, on by default per the
  brief; revisit only if a real need for image-preserving invert shows up.
- **PdfBox-Android is the biggest APK-size risk** (it bundles font/metrics
  assets loaded by runtime string path, which R8 can't shrink). Milestone 1's
  spike measured a release build (minify + resource shrinking on) with just
  the scaffold + PdfBox-Android dependency at **7.3MB**, well under the 15MB
  budget — re-check `assembleRelease` APK size whenever PdfBox-Android usage
  actually grows (real merge/compress/annotation code, Milestones 4-6).
- Only add reopenable documents to Recents: persisted SAF grants, the app's own
  FileProvider, or validated private imported copies. Temporary external handoffs
  are copied into `filesDir/imported_documents/` using source/content hashes.
  Repeated identical handoffs reuse their draft; changed sources keep a separate
  identity. Legacy URI-keyed drafts migrate atomically to the imported URI with
  their immutable base and preferences; old drafts and newer destination drafts
  are retained. Never write back to the temporary external source.

## Build / run / test commands

```sh
./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # install to running emulator/device
./gradlew test                          # JVM unit tests (Robolectric where needed)
./gradlew connectedAndroidTest          # instrumented tests (needs emulator/device)
./gradlew ktlintCheck / ktlintFormat    # lint / auto-format
```

Local dev environment on this machine (documented since neither Android
Studio nor a system JDK was preinstalled):

- JDK: Homebrew `openjdk@21`, keg-only — not on `PATH` by default. Export
  `JAVA_HOME=/opt/homebrew/opt/openjdk@21` and prepend `$JAVA_HOME/bin` to
  `PATH` before running Gradle commands directly (the Gradle wrapper also
  needs `JAVA_HOME` set).
- Android SDK: installed to `~/Library/Android/sdk` via the command-line
  tools (`sdkmanager`), not Android Studio. `local.properties` (gitignored)
  points at this SDK.
- Emulator: a headless AVD using `system-images;android-35;google_apis_playstore;arm64-v8a`.

## Milestones

1. Scaffold, theme, navigation, Home + recents (Room) + SAF open flow — **done**
2. Reading mode: rendering, scroll, zoom, page indicator, remembered position — **done**
3. Gesture system + animation framework — **done**
4. Writing mode: sign, text, draw, erase, undo/redo, save-as-copy — **done**
5. Page overview: reorder, delete, append — **done**
6. File actions: compress, share/export
7. Performance pass, edge cases, final licenses/README polish

Each milestone is built, run, and manually exercised on an emulator before
moving to the next, then committed with a clear message.

**Milestone 3 testing note:** `adb shell input swipe/tap` can only simulate
single-pointer gestures - it cannot drive real multi-touch. Edge-swipe-close,
edge-swipe-to-file-actions-sheet, long-press-for-slider, scroll, and
pinch/double-tap zoom were all verified live via adb. Two-finger vertical
swipe (mode toggle) and pinch-out-at-minimum-zoom (page overview) are
implemented but could only be verified by hand in the live emulator window
(the user tried pinch-zoom directly in Milestone 2's testing and it worked) -
if either misbehaves, check `PinchZoomGesture.kt`'s `TwoFingerClassification`
thresholds (`OVERVIEW_ZOOM_RATIO`, `MODE_SWIPE_THRESHOLD_PX`) first.

**Milestone 4 testing note:** writing mode's two/three-finger undo/redo and
mode-toggle swipe hit the same adb multi-touch limitation as Milestone 3, but
this time it was worked around properly instead of just hand-waved: see
`app/src/androidTest/java/com/pdfeditor/app/ui/writing/WritingGesturesTest.kt`,
which drives `writingGestures` with Compose's `performTouchInput` multi-pointer
API (`down(pointerId, pos)` / `moveTo` / `up`) - this dispatches real
multi-pointer `PointerInputChange` sequences through the actual gesture code,
so it's a genuine test of the classification logic, not a workaround. Run it
with `./gradlew :app:connectedDebugAndroidTest` against a running
emulator/device. Prefer this pattern over adb for any future multi-touch
verification.

Also found and fixed during manual testing: `writingGestures` originally
routed a stationary single-finger tap straight to the (empty) draw-stroke
branch whenever the Draw tool was active, so it never fell through to
`onTap` - meaning tapping the canvas to wake a hidden tool tray silently did
nothing while Draw was selected. Fixed by only taking the draw-stroke branch
when `drawPoints.size >= 2`. The tool tray auto-hides after 4s idle
(`WritingScreen.kt`'s `interactionTick`/`pokeInteraction`); a tap while
hidden always wakes it without performing the tool action - remember this
"first tap wakes, second tap acts" behavior when testing any tool by hand.

Save-as-copy (`NavGraph.kt`'s `onSaveCopy`) was verified end-to-end: opens a
fresh working copy in `filesDir/working_copies/<docId>.pdf` from the original
SAF uri, flattens annotations via `PdfBoxAnnotationFlattener`, then launches
`ActivityResultContracts.CreateDocument` for the destination - confirmed the
original file is untouched and the exported copy is a valid, larger PDF
(`file` reported `PDF document, version 1.4, 20 pages` with the appended
stroke/text operators present).

**Milestone 5 testing note:** `PageOverviewScreen`'s reorder/delete/append all
edit a private working copy via `PdfBoxEditor` (structural ops split out of
`PdfEditor`; `compress` moved to its own `PdfCompressor` interface, still
unimplemented, for Milestone 6) and reload `ReaderViewModel`'s renderer
against it - verified live: delete removed the right page and shifted
everything up, append pulled in another picked PDF's pages, and thumbnails/
pages correctly refreshed rather than showing stale content.

Two real bugs were found and fixed during that manual testing, both worth
remembering for future screens:
- The page overview grid's top row rendered under the status bar, and (per
  the Milestone 3 lesson) touches in that inset region never reach the app -
  the top row's delete badges were completely untappable on a real device.
  Fixed by adding `WindowInsets.systemBars.asPaddingValues()` to the grid's
  `contentPadding`. Any full-bleed `LazyVerticalGrid`/`LazyColumn` with
  interactive elements near an edge needs this.
- After a structural edit, a page's index can now map to entirely different
  content, but Compose's `remember(pageIndex, widthPx)` bitmap caches in
  `PdfPageItem` and `OverviewThumbnail` had no way to know that - they kept
  showing the stale bitmap for that index. Fixed by adding a
  `documentVersion: Int` to `ReaderUiState.Ready` (bumped on every
  reorder/delete/append) and keying those `remember`/`LaunchedEffect` blocks
  on it too. Any future per-index UI cache needs the same key.

Drag-to-reorder (long-press-then-drag) can't be driven by `adb shell input
swipe` at all - it starts moving the pointer immediately rather than holding
still first, which cancels `detectDragGesturesAfterLongPress` before it
recognizes the press. Verified instead with Compose's synthetic touch
injection and explicit timing control (`down` + `advanceEventTime(600)` past
the long-press threshold + `moveTo` + `up`) - see
`app/src/androidTest/java/com/pdfeditor/app/ui/reader/PageOverviewScreenTest.kt`.
Sharing a document with structural edits applied needed a `FileProvider`
(`androidx.core.content.FileProvider`, declared in `AndroidManifest.xml` with
`res/xml/file_paths.xml` exposing `filesDir/working_copies/`) since a private
`file://` path can't be handed to another app via `ACTION_SEND` directly.

## Out of scope for v1 (design so these can be added later; do not build them now)

Form field (AcroForm) filling, password/encryption support (show a "not
supported yet" message if an encrypted PDF is opened), OCR, cloud sync,
accounts, light theme, tabs, in-app file manager, text reflow,
comments/collab.
