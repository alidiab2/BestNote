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
├── extras/<name>.json       notebook-level records (format 5), e.g. smartboard.json
├── assets/
│   ├── <sha256>.pdf         imported PDFs (stored once, referenced by pages)
│   ├── <sha256>.webp        images
│   └── <sha256>.m4a         audio
└── thumbs/<pageId>.webp     optional cached thumbnails (safe to delete)
```

On disk inside the app, a notebook lives **unpacked** as a directory with the same layout, which makes partial writes cheap. The `.bnote` zip is produced for export, share and backup.

## Versioning
- `manifest.json` and every `pages/<id>.json` carry `"format": N` (current: **6**). Readers refuse versions newer than they know and offer "update the app".
- Older versions are upgraded in memory on read by `FormatMigrations` (one step per version, on the JSON tree); the next save writes the current version.
- v1 → v2: page metadata always states `paper` (v1 drafts could omit it, meaning White).
- v3 → v4: the manifest may list `recordings` (MEDIA-04): `{id, title?, startedAtMillis, durationMillis}`; each one's audio is `assets/audio/<id>.m4a` (AAC, mono). Absent means none, so the step only stamps the version.
- v4 → v5: the manifest may list `extras` (TPL-01): names of small notebook-level JSON records stored at `extras/<name>` (`[a-z0-9_-]{1,40}.json`), rewritten on every save. `smartboard.json` holds Smart Board's notebook glossary and, per insert, the template id, page ids and the page's notes in the compact element format (for follow-up photos). Absent means none, so the step only stamps the version.
- v5 → v6: a page may have an edit journal `pages/<id>.jrnl` next to its chunk (DOC-11, see *Page journal*). The JSON is unchanged, so the step only stamps the version; a v5 app refuses v6 notebooks rather than save over strokes that only live in a journal.
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
`RuledWide`, `Grid`, `Dotted`, `Cornell`, `MusicStaff`, `PlannerDaily`, `PlannerWeekly`, `Graph`), `widthPt`, `heightPt` (the page size in points,
already oriented, so A5 landscape is 595 x 420), `ink` (the chunk file name) and `paper` (`White`,
`Cream` or `Dark`; optional, missing or unknown reads as `White`). Ink coordinates are page-local points.

## `.ink` chunk v3 (implemented in `InkChunkCodec`; v1 and v2 are still read)
Big-endian; floats are raw IEEE-754 bits, so round-trips are bit-exact. The family key (e.g. `fountain.v1`) is the brush family captured at pen-down, so strokes keep rendering identically after brush tuning.
```
header   "BNIK" | u16 version | u16 flags (0) | u32 payload length | u32 CRC32(payload)
payload  u32 strokeCount, then per stroke:
           u8 tool (0 Pen, 1 Pencil, 2 Highlighter) | u8 penStyle (0 Fountain, 1 Ball, 2 Brush)
           i32 colorArgb | f32 size | u8 familyKeyLength | familyKeyLength x UTF-8 (v2+; 0 = none)
           u8 audioIdLength (v3 only; 0 = none) | audioIdLength x UTF-8 recording id | i64 offsetMillis (only when the id is present)
           u32 sampleCount
           sampleCount x (f32 x | f32 y | f32 pressure | f32 tiltRad | f32 orientationRad | i64 timeMillis)
```
Versions: writers always write v3. The audio stamp (MEDIA-05) says the stroke was written `offsetMillis` after the recording started; strokes written with no recording have none. Readers accept v1 (DOC-01 builds: no family key, read as `family = null`, drawn with the tool's current family) and v2. The key maps to `InkBrush.family` in `:core:model`.

An androidx.ink storage encoding can be added later as a new chunk version behind `StrokeChunkCodec`.

## Open questions
- Layers as separate `.ink` files or as sections in one chunk?


## Page journal (DOC-11)
Autosave appends small records to `pages/<id>.jrnl` instead of rewriting the whole `.ink` chunk; the chunk is rewritten (compaction) when the journal passes 4 MB or 2,000 edits, after 30 s without edits, and when the notebook is closed. Load is the chunk plus a replay of the journal; every reader (`FileNotebookStore.read`, hence export and backup) sees the replayed page, and `compactJournals` folds leftovers into the chunk before an editor opens a notebook.

```
header  "BNJL" | u16 version (1) | u16 flags (0) | u32 baseId        baseId = CRC32 stored in the chunk header
record  u32 payloadLength | u32 CRC32(payload) | payload
payload u32 baseCount | u32 removedCount, removed x u32 | u32 addedCount, added x u32 | u32 chunkLength | .ink chunk of the added strokes
```
A record removes strokes by index (in the list before the edit) and inserts the new ones at their final indices; `baseCount` must equal the current stroke count. Records are checksummed and fsynced when appended. Replay stops at the first record that is short, fails its checksum or does not fit, so a torn last write loses only that edit. A journal whose `baseId` differs from the chunk's is stale (crash between "chunk written" and "journal deleted") and is ignored and deleted.
