# WS-07 Library & Organization (`LIB`)

**Goal:** find any notebook in two seconds. The library should look like a bookshelf, not a file manager.
**Owns:** `:feature:library`
**Reference:** [Goodnotes organization research](../research/goodnotes/08-organization.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] LIB-01 | Library grid/list with covers and thumbnails, adaptive columns | M1 | M | DOC-02, DS-01 | Smooth at 1,000 items |
| [x] LIB-02 | New notebook sheet: title, cover, paper template, size | M1 | M | PAGES-01 | Cover → editor uses a shared-element "open book" transition |
| [x] LIB-03 | Rename, delete (to Trash with 30-day restore), undo snackbar | M1 | S | — | — |
| [x] LIB-04 | Folders and subfolders, move by drag-and-drop | M2 | L | — | — |
| [x] LIB-05 | Favorites + Quicknote (one tap from launcher shortcut) | M2 | S | — | Launcher long-press → Quicknote opens straight to a blank page |
| [x] LIB-06 | Sort (name, modified, created) and filter (type, folder, color) | M2 | S | — | — |
| [x] LIB-07 | Title search with instant results | M2 | S | — | Arabic normalization (أ/إ/آ → ا, ة → ه) |
| [ ] LIB-08 | Home-screen widgets: Favorites, Quicknote (Glance) | M3 | M | LIB-05 | — |
| [ ] LIB-09 | Drop PDFs/images from other apps into the library | M3 | S | PDF-01 | — |
| [x] LIB-10 | Notebook color tags and custom covers from image | M2 | S | — | — |
| [x] LIB-11 | Lock a notebook or folder: biometric / screen lock (app PIN without one), AES-GCM encryption at rest, optional recovery password, relock timer | M3 | L | DOC-11 | Locked = lock cover, not in search / Continue writing / Recent, FLAG_SECURE; files on disk not plaintext; lock → unlock → remove lock keeps every byte ([design](../architecture/locked-notebooks.md)). LIB-11b gaps closed: recordings encrypted while recorded and played, PDFs opened without a plain copy, no plaintext caches / index / AI log for a locked notebook |
