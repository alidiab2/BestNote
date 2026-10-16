# WS-05 PDF (`PDF`)

**Goal:** the best PDF annotation on Android tablets, fast with huge lecture slide decks.
**Owns:** `:engine:pdf`
**Decision first:** [ADR-0005 PDF engine](../architecture/adr/0005-pdf-engine.md)
**Reference:** [Goodnotes PDF research](../research/goodnotes/05-pdf-and-documents.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] PDF-01 | Import via share sheet, SAF picker and drag-and-drop; copy into the notebook package (done: picker, Open with, share; drag-and-drop later) | M2 | M | DOC-01 | Importing a 500-page PDF returns to the UI in < 1 s (pages render lazily) |
| [ ] PDF-02 | (done: background-thread render at page-slot width, byte-bounded LRU, low-res placeholder, and past that resolution each CANVAS-06 tile renders its own PDF region, sharp at 500 % ([before/after](../qa/tiles/pdf02-500pct-before-after.png)); open: an OOM check on a real 4 GB device) Tiled render pipeline: zoom-dependent resolution, background thread, LRU bitmap pool | M2 | L | CANVAS-06 | Text stays sharp at 400%; no OOM on 4 GB devices |
| [x] PDF-03 | Ink annotation layer over PDF pages (ink is stored per page in page points, the page is the PDF page's size) | M2 | M | PDF-02 | Ink aligns exactly at every zoom and after rotation |
| [ ] PDF-04 | Text-aware highlighter (snaps to text lines when a text layer exists) | M2 | L | INK-03 | Highlight follows the line even when hand-drawn slightly off |
| [ ] PDF-05 | Export with annotations (flattened or as PDF annotations) | M2 | L | — | Opens correctly in Acrobat, Chrome and Samsung Notes |
| [ ] PDF-06 | Outline (table of contents) panel, multi-level | M3 | M | — | — |
| [ ] PDF-07 | Bookmarks | M3 | S | — | — |
| [ ] PDF-08 | Follow PDF links; user-created links between pages and notebooks | M3 | M | — | — |
| [ ] PDF-09 | Multiple open documents as tabs | M3 | M | DS-05 | Switching tabs keeps zoom and scroll position |
| [ ] PDF-10 | Form filling | M4 | L | — | — |
| [ ] PDF-11 | Text search in PDF text layer | M2 | M | — | Results highlighted with a jump list |
