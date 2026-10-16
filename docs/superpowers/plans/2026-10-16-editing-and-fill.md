# Editing fixes, explicit saving, and field suggestions

**Goal:** Make every added object movable, restore zoomed navigation, provide explicit discard, and publish an APK-only bug-fix release before adding fill suggestions.

**Architecture:** Retain Compose, PDFBox, immutable renderer bases and private editable drafts. Only explicit save writes a destination PDF. Temporary external grants become validated private imported copies for durable recents. Keep all processing offline and current dependency/license constraints.

## First release

- [ ] Add stroke/signature move and zoomed single-finger pan regressions; fix gesture arbitration without breaking selection, normal scrolling or multitouch.
- [ ] Replace gradient header with solid black; retain camera-safe compact controls.
- [ ] Add discard to DocumentEdits, ReaderViewModel and save menu; restore saved snapshot, clear history and persist reverted draft. Test structure, annotation, reopen and original bytes.
- [ ] Verify close/navigation only persist private draft, never source; retain editable objects after export. Clarify requested leave behavior with user.
- [ ] Make temporary external handoffs durable, validated, deduplicated and present in recents; label imported copies and preserve durable SAF behavior.
- [ ] Run JVM, instrumented, lint, optimized release and signed upgrade checks. Update docs; bump v0.2.3, push and publish signed APK only, no screenshot assets.

## Then field suggestions

- [ ] Inspect AcroForm widget geometry and PDF text/vector cues using existing PDFBox. Detect empty text/signature fields first; conservative printed box/line inference is advisory and offline.
- [ ] Offer contextual tap-to-fill suggestions that place normal editable text/signature annotations; never silently write or flatten PDF content.
- [ ] Test rotated/cropped coordinates, false positives, existing entries, saved/recovered annotations, and integration through normal save/discard behavior.
- [ ] Validate and document detection scope accurately. Keep post-release changes separate from the published fixes unless a further release is requested.
