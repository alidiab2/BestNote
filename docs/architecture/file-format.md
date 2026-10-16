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
│   └── <pageId>.ink         binary strokes for the page (our sample encoding + header, see below)
├── assets/
│   ├── <sha256>.pdf         imported PDFs (stored once, referenced by pages)
│   ├── <sha256>.webp        images
│   └── <sha256>.m4a         audio
└── thumbs/<pageId>.webp     optional cached thumbnails (safe to delete)
```

On disk inside the app, a notebook lives **unpacked** as a directory with the same layout, which makes partial writes cheap. The `.bnote` zip is produced for export, share and backup.

## Versioning
- `manifest.json` and every `pages/<id>.json` carry `"format": N` (current: **2**). Readers refuse versions newer than they know and offer "update the app".
- Older versions are upgraded in memory on read by `FormatMigrations` (one step per version, on the JSON tree); the next save writes the current version.
- v1 → v2: page metadata always states `paper` (v1 drafts could omit it, meaning White).
- Every migration ships as a test fixture (`core/storage/src/test/resources/fixtures/v<N>-notebook/`) with a test in `FormatMigrationTest`.

## Backup (DOC-08)
A backup folder holds `backup.json` (written last), `notebooks/<id>-<updatedAt>.bnote`, `trash/…` and `extras/library/metadata.json` (folders, covers, favorites). It is incremental and never deletes on restore; see `LibraryBackup`.

## Write safety
- Write to `<file>.tmp`, `fsync`, then atomic rename.
- `manifest.json` is written last, so a crash leaves the previous manifest pointing at valid pages.
- Each `.ink` chunk has a header with magic bytes `BNIK`, the chunk version and a CRC32.
- Autosave (`Autosaver`, DOC-03) rewrites only the pages edited since the last save, then the manifest. A crash leaves every page either fully old or fully new.
- Inside the app, library thumbnails are cached outside the package (`files/thumbnails/<id>.webp`, DOC-10); `thumbs/` stays optional for exports.

## Page metadata (`pages/<pageId>.json`)
`format`, `id`, `index`, `template` (a `PaperTemplate` name: `Blank`, `Ruled` (college), `RuledNarrow`,
`RuledWide`, `Grid`, `Dotted`, `Cornell`, `MusicStaff`), `widthPt`, `heightPt` (the page size in points,
already oriented, so A5 landscape is 595 x 420), `ink` (the chunk file name) and `paper` (`White`,
`Cream` or `Dark`; optional, missing or unknown reads as `White`). Ink coordinates are page-local points.

## `.ink` chunk v2 (implemented in `InkChunkCodec`; v1 is still read)
Big-endian; floats are raw IEEE-754 bits, so round-trips are bit-exact. The family key (e.g. `fountain.v1`) is the brush family captured at pen-down, so strokes keep rendering identically after brush tuning.
```
header   "BNIK" | u16 version | u16 flags (0) | u32 payload length | u32 CRC32(payload)
payload  u32 strokeCount, then per stroke:
           u8 tool (0 Pen, 1 Pencil, 2 Highlighter) | u8 penStyle (0 Fountain, 1 Ball, 2 Brush)
           i32 colorArgb | f32 size | u8 familyKeyLength | familyKeyLength x UTF-8 (v2 only; 0 = none) | u32 sampleCount
           sampleCount x (f32 x | f32 y | f32 pressure | f32 tiltRad | f32 orientationRad | i64 timeMillis)
```
Versions: writers always write v2. Readers accept v1 (DOC-01 builds: no family key, read as `family = null`, drawn with the tool's current family) and v2. The key maps to `InkBrush.family` in `:core:model`.

An androidx.ink storage encoding can be added later as a new chunk version behind `StrokeChunkCodec`.

## Open questions
- Store per-stroke timestamps relative to an audio recording (needed for MEDIA-05)?
- Layers as separate `.ink` files or as sections in one chunk?
