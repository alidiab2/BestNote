# WS-03 Canvas & Editing (`CANVAS`)

**Goal:** a page surface that stays smooth with thousands of strokes, plus editing tools that feel direct.
**Owns:** `:engine:canvas`, the canvas host in `:feature:editor`
**Reference:** [Goodnotes editing research](../research/goodnotes/02-editing-and-selection.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [ ] CANVAS-01 | Pixel eraser: splits strokes along the eraser path | M1 | L | — | Erasing through a word leaves clean stroke ends; undo restores exactly |
| [ ] CANVAS-02 | Stroke eraser + "erase highlighter only" option | M1 | S | — | Touching a stroke removes it whole |
| [x] CANVAS-03 | Undo/redo command stack per document (bounded, survives tool switches) | M1 | M | — | 200 operations undo/redo correctly; memory stays bounded |
| [ ] CANVAS-04 | Gestures: two-finger tap undo, three-finger tap redo | M1 | S | SPEN-02 | Gestures don't trigger while writing |
| [ ] CANVAS-05 | Zoom & pan: pinch, fling, double-tap fit-to-width, zoom limits 25%–800% | M1 | L | — | 120 fps pan on a full page; ink sharp at every zoom |
| [ ] CANVAS-06 | Dry-ink tile cache: rasterize finished strokes into tiles per zoom bucket | M1 | L | CANVAS-05 | Page with 5k strokes redraws in < 8 ms |
| [x] CANVAS-07 | Paginated vertical document view with page recycling | M1 | L | DOC-01 | Scrolling a 300-page notebook stays at 120 fps, memory flat |
| [ ] CANVAS-08 | Lasso select (freehand) with spatial index hit-testing | M2 | M | — | Selects partially enclosed strokes by a > 50% rule |
| [ ] CANVAS-09 | Transform selection: move, resize, rotate with handles and snapping | M2 | L | CANVAS-08 | Handles animate in; rotation snaps at 0/45/90° with a haptic tick |
| [ ] CANVAS-10 | Restyle selection: color, thickness, pen type | M2 | S | CANVAS-08 | One-tap recolor of a lasso selection |
| [ ] CANVAS-11 | Cut/copy/paste/duplicate, across pages, notebooks and app instances | M2 | M | CANVAS-08 | Paste lands where the pen last touched |
| [ ] CANVAS-12 | Layers (add, hide, reorder, per-layer lock) | M3 | XL | DOC-05 | Split into sub-tasks before starting |
| [ ] CANVAS-13 | Lock objects (images, shapes, PDF backgrounds) | M3 | S | CANVAS-08 | Locked items ignore lasso and eraser |
| [ ] CANVAS-14 | Group/ungroup, bring to front / send to back | M3 | M | CANVAS-08 | — |
| [ ] CANVAS-15 | Gesture tools: scribble-to-erase, circle-to-lasso | M3 | M | REC-01 | False-positive rate < 1% in the test corpus |
| [ ] CANVAS-16 | Zoom window (magnified writing strip with auto-advance) | M3 | L | CANVAS-05 | — |
