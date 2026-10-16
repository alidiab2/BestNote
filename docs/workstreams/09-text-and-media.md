# WS-09 Text & Media (`TEXT`, `MEDIA`)

**Goal:** mix typed text, images and audio with handwriting without breaking the pen flow.
**Owns:** page element types (text box, image, audio) across `:core:model`, `:engine:canvas`, `:feature:editor`
**Reference:** [text research](../research/goodnotes/03-handwriting-and-text.md), [media research](../research/goodnotes/07-media-and-elements.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] TEXT-01 | Text box element: insert, edit, move, resize | M2 | M | CANVAS-08 | — |
| [x] TEXT-02 | Formatting: font, size, color, bold/italic/underline, alignment, lists | M2 | M | TEXT-01 | Arabic text shapes and aligns correctly |
| [ ] TEXT-03 | Typed text document type | M4 | L | TEXT-02 | — |
| [x] TEXT-04 | Keyboard typing and design: tap-to-type on the canvas (in place, IME/bidi), format bar above the keyboard, paragraph styles (Title…Code), Text shortcuts + Markdown autoformat, snap guides + arrow nudging, caret kept above the keyboard | M2 | L | TEXT-02, SPEN-10 | Paragraph styles persist in `.bnote` v9; v8 files still read |
| [x] MEDIA-01 | Insert image: picker, camera, paste, drag-and-drop | M2 | M | — | Large images downsampled and stored once per notebook |
| [x] MEDIA-02 | Crop, rotate, resize image; lock image | M2 | M | MEDIA-01 | — |
| [ ] MEDIA-03 | Local sticker packs (files, importable) | M3 | M | MEDIA-01 | — |
| [x] MEDIA-04 | Audio recording in a notebook (foreground service, background-safe) | M3 | L | — | — |
| [x] MEDIA-05 | Audio ↔ ink sync: tap a stroke to play from that moment | M3 | L | MEDIA-04 | Strokes carry recording timestamps |
| [ ] MEDIA-06 | Document scan to page (edge detect, perspective fix) | M4 | L | — | Must work without Play Services in the F-Droid build |
