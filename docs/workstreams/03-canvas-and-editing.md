# WS-03 Canvas & Editing (`CANVAS`)

**Goal:** a page surface that stays smooth with thousands of strokes, plus editing tools that feel direct.
**Owns:** `:engine:canvas`, the canvas host in `:feature:editor`
**Reference:** [Goodnotes editing research](../research/goodnotes/02-editing-and-selection.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] CANVAS-01 | Pixel eraser: splits strokes along the eraser path | M1 | L | — | Erasing through a word leaves clean stroke ends; undo restores exactly |
| [x] CANVAS-02 | Stroke eraser + "erase highlighter only" option | M1 | S | — | Touching a stroke removes it whole |
| [x] CANVAS-03 | Undo/redo command stack per document (bounded, survives tool switches) | M1 | M | — | 200 operations undo/redo correctly; memory stays bounded |
| [x] CANVAS-04 | Gestures: two-finger tap undo, three-finger tap redo | M1 | S | SPEN-02 | Gestures don't trigger while writing |
| [x] CANVAS-05 | Zoom & pan: pinch, fling, double-tap fit-to-width, zoom limits 25%–800% (smooth-zoom follow-up: no main-thread rasterization during a pinch — stand-ins from any zoom scale or a per-page overview, sharp tiles only after the gesture settles, new pages measured off the main thread; `ZoomStallBenchmark`, debug `PERF` frame-time probe) | M1 | L | — | 120 fps pan on a full page; ink sharp at every zoom |
| [x] CANVAS-06 | Dry-ink tile cache: rasterize finished strokes into tiles per zoom bucket (done: 512 px tiles per page and exact zoom scale incl. paper/PDF/elements, two workers, diff-based invalidation, overlay of new ink, previous-zoom stand-in; `TileRedrawBenchmark`: 5,456-stroke page records in 0.07 ms vs 180 ms) | M1 | L | CANVAS-05 | Page with 5k strokes redraws in < 8 ms |
| [x] CANVAS-07 | Paginated vertical document view with page recycling | M1 | L | DOC-01 | Scrolling a 300-page notebook stays at 120 fps, memory flat |
| [x] CANVAS-08 | Lasso select (freehand) with spatial index hit-testing | M2 | M | — | Selects partially enclosed strokes (≥ 10% of length or ≥ 24 pt enclosed) |
| [x] CANVAS-09 | Transform selection: move, resize, rotate with handles and snapping | M2 | L | CANVAS-08 | Handles animate in; rotation snaps at 0/45/90° with a haptic tick |
| [x] CANVAS-10 | Restyle selection: color, thickness, pen type | M2 | S | CANVAS-08 | One-tap recolor of a lasso selection; "Pen" restyles to another pen type, one undo step |
| [x] CANVAS-11 | Cut/copy/paste/duplicate, across pages, notebooks and app instances | M2 | M | CANVAS-08 | Paste lands where the pen last touched (app instances via the system clipboard: follow-up) |
| [ ] CANVAS-12 | Layers (add, hide, reorder, per-layer lock) | M3 | XL | DOC-05 | Split into sub-tasks before starting |
| [x] CANVAS-13 | Lock objects (images, shapes, PDF backgrounds) | M3 | S | CANVAS-08 | Locked items ignore lasso and eraser |
| [x] CANVAS-14 | Group/ungroup, bring to front / send to back | M3 | M | CANVAS-08 | — |
| [ ] CANVAS-15 | Gesture tools: scribble-to-erase, circle-to-lasso | M3 | M | REC-01 | False-positive rate < 1% in the test corpus |
| [ ] CANVAS-16 | Zoom window (magnified writing strip with auto-advance) | M3 | L | CANVAS-05 | — |
| [x] CANVAS-17 | Shapes: live ghost before lift (hold-to-shape preview with label and snap hints, follows the pen, shake/move away cancels), better recognition (diamonds, stars, open circles, sloppy boxes; accuracy harness over `shapes_sloppy` corpus: 76% → 98%), glue (shapes snap corner-to-corner / edge-to-edge, line ends attach) and connectors that follow moved/resized shapes (`ShapeInfo` on `InkBrush`, `.ink` chunk v6) | M3 | L | INK-08, REC-09 | Lifting commits exactly the ghost; connectors stay attached through move/resize and undo |
