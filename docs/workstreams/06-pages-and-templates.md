# WS-06 Pages & Templates (`PAGES`)

**Goal:** paper that looks printed, and page management that feels physical.
**Owns:** paper templates (`PaperTemplate` in `:core:model`, renderers in `:engine:canvas`), page manager UI in `:feature:editor`
**Reference:** [Goodnotes pages research](../research/goodnotes/06-pages-and-templates.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] PAGES-01 | Templates: blank, ruled (narrow/college/wide), grid, dotted; paper color incl. dark paper | M1 | M | — | Lines stay 1 physical px crisp at all zoom levels |
| [x] PAGES-02 | Paper sizes: A4, A5, Letter, "fit to screen", custom; portrait/landscape | M1 | S | — | — |
| [x] PAGES-03 | Page thumbnails sidebar / grid overview | M2 | M | DOC-10 | Opens with a shared-element zoom-out animation |
| [x] PAGES-04 | Add, delete, reorder (drag), duplicate, rotate pages | M2 | M | PAGES-03 | Reorder by drag with spring physics; undoable |
| [x] PAGES-05 | Insert page: template picker, from PDF (all pages or a range), from image | M2 | M | PDF-01 | — |
| [x] PAGES-06 | Template pack 1: Cornell, music staff, planner (daily/weekly), graph | M2 | M | PAGES-01 | — |
| [ ] PAGES-07 | Infinite canvas notebook type | M3 | XL | CANVAS-06 | Split before starting |
| [ ] PAGES-08 | Custom templates from image/PDF, saved for reuse | M3 | M | PAGES-05 | — |
| [ ] PAGES-09 | Horizontal paging and two-page spread view | M3 | M | CANVAS-07 | — |
