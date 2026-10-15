# WS-04 Documents & Storage (`DOC`)

**Goal:** never lose a stroke. Open files fast and keep the format open and documented.
**Owns:** `:core:storage`, `:core:database`, `:core:datastore`
**Spec:** [`../architecture/file-format.md`](../architecture/file-format.md), [ADR-0004](../architecture/adr/0004-storage.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] DOC-01 | `.bnote` v1 codec: manifest + per-page stroke chunks (androidx.ink storage encoding) | M1 | L | — | Round-trip tests: encode → decode is lossless for 10k random strokes |
| [x] DOC-02 | Room metadata index (notebooks, folders, page count, thumbnails, last opened) | M1 | M | — | Library loads 1,000 notebooks in < 200 ms |
| [x] DOC-03 | Autosave: debounced, per-page dirty tracking, atomic write (temp + rename) | M1 | M | DOC-01 | Killing the process mid-write never corrupts a file (fault-injection test) |
| [ ] DOC-04 | Crash recovery + corrupt-chunk isolation (one bad page never kills the notebook) | M1 | M | DOC-03 | Corrupted page opens as "damaged page" placeholder |
| [ ] DOC-05 | Format versioning + migration framework | M2 | M | DOC-01 | v1 → v2 migration test fixture in repo |
| [ ] DOC-06 | Export to PDF (vector strokes, PDF backgrounds preserved) | M3 | L | PDF-05 | Exported PDF text stays selectable; ink is vector |
| [ ] DOC-07 | Export page(s) to PNG / share sheet | M3 | S | — | — |
| [ ] DOC-08 | Full backup/restore to a user-chosen folder (SAF) | M3 | M | DOC-05 | Restore on a clean install reproduces the library exactly |
| [ ] DOC-09 | Optional sync through user-owned storage (SAF folder, WebDAV) | M4 | XL | DOC-08 | Needs a design doc first |
| [x] DOC-10 | Thumbnail generation pipeline (background, cached, invalidated on edit) | M1 | M | DOC-02 | Thumbnails update within 1 s of closing a notebook |
