# Document controls and selective page import - 16 October 2026

The camera/status inset remains solid black. The fade starts immediately below
it, across the compact controls and a 16dp tail. At the document start, a blank
paper margin behind the gradient keeps the first page below the controls while
making the fade visible. Header text has a small contrast shadow.

Document options morph to a larger, sharper preview above a bottom black icon
panel. Page/view and file actions use separate rows. The filename edits inline;
Done confirms it, and dismissing cancels unconfirmed text. The panel scrolls when
height is limited and respects camera, navigation, and keyboard insets. Pages
enters with a short fade, slide, and scale transition.

The page grid's Add pages action opens Android's PDF picker, then offers all
source pages or an individual thumbnail selection. Selection survives state
restoration. VM-owned import status prevents duplicate confirmation and retains
the selection on failure. Imports preserve source order, vectors, editable app
annotations, foreign annotations, CropBox/rotation, and the original source
bytes. Each addition is one undo step. A separate preview file and serialized
renderer keep preview reads independent from PDFBox, with cancellation-safe
resource acquisition and cleanup.

A curved right-edge handle and its label replace the bottom page counter. It
appears while scrolling, stays briefly after scrolling stops, and supports touch
and accessibility scrubbing. Its counter follows the mostly visible page, while
reading-position persistence still uses the first visible page and its offset. The track accounts for page heights, gaps, zoom,
system insets, and bottom controls; only the handle intercepts touches.

## Automated validation

JDK 21 and an isolated Android 15/API 35 arm64 emulator, with a camera cutout,
were used. The existing development emulator's data was retained.

- 113 JVM tests per debug/release variant, with zero failures or errors.
- Full 48-test instrumented run: zero failures, errors, or skips.
- Ten focused UI checks passed after final preview/counter changes, including a
  new 49th regression for the mostly visible page and the final page at the bottom.
- The full instrumented run completed in 7 minutes.
- New tests cover selected/whole-source imports, annotation/vector preservation,
  validation, undo/redo/recovery, duplicate-import protection, selection state
  restoration, scrollbar bounds/cancellation/accessibility/pass-through/idle,
  inline rename confirmation/cancellation, and the real native keyboard.

The rename keyboard regression failed before the fix: Android's default pan
mode moved the whole reader and left a large empty panel. Explicit resize mode
and measuring the actual panel position keep the filename immediately above
the keyboard. The short-screen test was updated for the new Compress label and
continues to exercise reachability through the panel's scroll container.

Code review found and resolved cancellation during native preview acquisition.
This was verified by code inspection; no native resource stress benchmark is
claimed. Existing Compose deprecation and R8 metadata warnings remain.

These checks do not establish physical-device, API 26, OCR, arbitrary Unicode
shaping, or every foreign-annotation rendering support.

## Manual and independent PDF checks

The native SAF flow added source pages 1 and 3 from a three-page Field notes PDF
to a three-page Project notes PDF. The page grid showed five pages. Save as new
file produced a 40,869-byte PDF. macOS PDFKit independently confirmed all five
pages in order, excluded the unselected Observations page, and extracted the
original searchable text. A rendered imported page was visually inspected.
Both source SHA-256 hashes remained unchanged:

- Project notes: `40f887f2e89312577c6531ce7e06c0aed975ab8663c6033437f2fbcbeba44011`
- Field notes: `93450647c9623b78405a2c7835655ee3e1497842d1b036e964bac1e4536c1186`

The signed build reopened that five-page copy, displayed its content, and
confirmed inline display-name editing with a full-sized preview above the native
keyboard. Its source picker offered all pages or a selected subset; Cancel kept
the document at five pages. Screenshots use only these synthetic documents.

The final signed APK was exercised with real adb touch input. The curved handle
was dragged from the middle to the bottom (5 / 5), then to the top (1 / 5); the
header returned at the start. No bottom page-counter overlay appeared. The
updated reader remains open on the isolated emulator.

## Release artifact: v0.2.2

The release version is 0.2.2/code 4. Fresh ktlint, 113 JVM tests per debug/release
variant, release vital lint, and the optimized release build passed after the
version bump. The signed APK is 8,662,627 bytes (8.26 MiB). APK v2/v3
signatures and zip alignment verify with the same release certificate as v0.2.1.
Manifest inspection shows minimum API 26, target API 36, and no Internet or broad
storage permission.

The signed APK upgraded the isolated API 35 emulator in place from version
0.2.1/code 3 to 0.2.2/code 4. The existing document and recents were retained.

SHA-256: `f367b499fc7d8b88114c7eb1767a8ae26141559a25898fdacf0c58092f7bbf19`

The GitHub release includes the signed APK, SHA256SUMS, release notes, and eight
screenshots. Signing credentials remain outside the repository.
