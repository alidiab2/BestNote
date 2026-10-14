# `.bnote` File Format (draft v1)

Status: **Draft**. Owner: WS-04 (`DOC-01`). The format is public, so other apps can read BestNote files and users are never locked in.

## Goals
- Lossless round-trip of strokes (every sample, pressure, tilt, timestamp).
- Open a notebook without reading every page (lazy page loading).
- One corrupted page never makes the rest unreadable.
- Easy to back up and share as a single file.

## Container
A `.bnote` file is a **ZIP** archive (stored, not compressed, for page chunks that are already compact; deflate for JSON):

```
notebook.bnote
├── manifest.json            format version, notebook id, title, created/modified, page order, cover
├── pages/
│   ├── <pageId>.json        page metadata: size, template, background ref, layer list, element list
│   └── <pageId>.ink         binary strokes for the page (androidx.ink storage encoding + our header)
├── assets/
│   ├── <sha256>.pdf         imported PDFs (stored once, referenced by pages)
│   ├── <sha256>.webp        images
│   └── <sha256>.m4a         audio
└── thumbs/<pageId>.webp     optional cached thumbnails (safe to delete)
```

On disk inside the app, a notebook lives **unpacked** as a directory with the same layout, which makes partial writes cheap. The `.bnote` zip is produced for export, share and backup.

## Versioning
- `manifest.json` → `"format": 1`. Readers must refuse versions newer than they know and offer "update the app".
- Every migration ships as a test fixture (`core/storage/src/test/resources/fixtures/v<N>/`).

## Write safety
- Write to `<file>.tmp`, `fsync`, then atomic rename.
- `manifest.json` is written last, so a crash leaves the previous manifest pointing at valid pages.
- Each `.ink` chunk has a header with magic bytes `BNIK`, the chunk version and a CRC32.

## Open questions
- Store per-stroke timestamps relative to an audio recording (needed for MEDIA-05)?
- Layers as separate `.ink` files or as sections in one chunk?
