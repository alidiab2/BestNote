# Roadmap

Milestones are scoped by **exit criteria**, not dates. A milestone ships when every criterion passes on the reference devices (see [`platform/samsung-s-pen.md`](platform/samsung-s-pen.md#device-matrix)).

Task IDs (e.g. `INK-03`) point to the workstream files in [`workstreams/`](workstreams/).

## M0: Foundation (walking skeleton)
The multi-module project builds, draws a pressure-sensitive stroke, and CI is green.
- [x] Module layout, convention plugins, version catalog
- [x] Library → Editor navigation, basic ink canvas
- [ ] CI: build, unit tests, lint on every PR (`QA-01`)
- [ ] Decisions logged: license, PDF engine, recognition engine (see "Open decisions" below)

## M1: "It writes beautifully" (internal alpha)
The core loop: open a notebook, write, erase, undo, close, reopen, and everything is still there.
- Ink engine with Fountain/Ball/Brush pens, Pencil, Highlighter (`INK-01..06`)
- S Pen: pressure, tilt, hover cursor, side-button eraser, palm rejection (`SPEN-01..06`)
- Pixel and stroke eraser, undo/redo with gestures (`CANVAS-01..04`)
- Zoom/pan, paginated vertical scroll, paper templates (`CANVAS-05..07`, `PAGES-01..02`)
- `.bnote` storage with autosave, crash safety and thumbnails (`DOC-01..04`, `DOC-10`)
- Library: create, rename, delete, covers (`LIB-01..03`)
- Design system v1 and motion tokens (`DS-01..04`)
- Quality gates, benchmarks, ink-latency harness, device matrix (`QA-02..04`, `QA-09`, `SPEN-13`)

**Exit:** writing latency ≤ 1 frame on Tab S9/S10; 50-page notebook round-trips without loss; no crash in a 30-minute write session.

## M2: "Daily driver" (public beta)
- Lasso: select, move, resize, rotate, recolor, copy/paste (`CANVAS-08..11`)
- Shapes (auto-snap), straight lines (`INK-07..08`)
- PDF import, annotate, export with annotations (`PDF-01..05`)
- Page manager: thumbnails, reorder, duplicate, insert template pages (`PAGES-03..06`)
- Folders, favorites, sorting, global title search (`LIB-04..07`)
- Images: insert, crop, move (`MEDIA-01..02`)
- Multi-window, DeX, keyboard shortcuts (`SPEN-08..10`)
- Text boxes (`TEXT-01..02`)
- **Smart Board AI**: photo of a whiteboard → expert-student notes (opt-in, bring-your-own-key) (`AI-03..07`, `AI-09`)
- Recognition engine chosen on an Arabic + English corpus (`REC-01`), shape snapping engine (`REC-09`)

**Exit:** a student can run a full semester on BestNote. 500-page PDF opens in < 1 s.

## M3: v1.0
- Handwriting search (on-device recognition), Arabic and English (`REC-02..04`)
- Layers, locked objects, group/ungroup (`CANVAS-12..14`)
- Outlines, bookmarks, internal links, document tabs (`PDF-06..09`)
- Infinite canvas notebooks (`PAGES-07`)
- Audio recording synced to ink (`MEDIA-04..05`)
- Export: PDF, PNG, `.bnote` share; full backup/restore (`DOC-06..08`)
- Release on Google Play and F-Droid (`QA-06..08`)

## M4: Beyond Goodnotes
- Handwriting → text conversion, reflow, spellcheck (`REC-05..07`)
- Math Assist (`REC-08`)
- S Pen Air actions (Samsung flavor) (`SPEN-11`)
- Optional sync through user-owned storage (WebDAV / SAF folder) (`DOC-09`)
- Plugin/template packs as files

## Open decisions
| # | Decision | Options | Owner | Status |
|---|---|---|---|---|
| D1 | License | Apache-2.0 · GPL-3.0 · MIT | Maintainer | **Decided: MIT** (see `LICENSE`) |
| D2 | PDF engine for M2 | Android `PdfRenderer` (built-in, limited) · PDFium (BSD, fast, larger APK) · MuPDF (AGPL, best quality, forces AGPL) | WS PDF | Proposed: [ADR-0005](architecture/adr/0005-pdf-engine.md) |
| D3 | Handwriting recognition | ML Kit Digital Ink (on-device, proprietary, needs Play Services) · open model (e.g. ONNX) | WS Recognition | Proposed: [ADR-0006](architecture/adr/0006-recognition.md) |
| D5 | Optional cloud AI (Smart Board) | BYOK provider modules outside `foss` · on-device only | Maintainer | Proposed: [ADR-0007](architecture/adr/0007-optional-cloud-ai.md) |
| D4 | Samsung S Pen Remote SDK | Separate `samsung` build flavor · skip | WS S Pen | Proposed: flavor, M4 |
