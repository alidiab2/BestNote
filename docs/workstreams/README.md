# Workstreams

The work is split into **12 workstreams**. Each one owns specific modules, so people can work in parallel without stepping on each other. Pick one workstream per person or agent. Inside a workstream, take tasks in ID order unless the "Depends on" column says otherwise.

| WS | Name | Prefix | Owns modules | Start in |
|---|---|---|---|---|
| [01](01-ink.md) | Ink & Brushes | `INK` | `:engine:ink` | M1 |
| [02](02-spen-and-input.md) | S Pen, Input & Samsung | `SPEN` | `:engine:stylus`, window/input code in `:app` | M1 |
| [03](03-canvas-and-editing.md) | Canvas & Editing | `CANVAS` | `:engine:canvas`, canvas parts of `:feature:editor` | M1 |
| [04](04-documents-and-storage.md) | Documents & Storage | `DOC` | `:core:storage`, `:core:database`, `:core:datastore` | M1 |
| [05](05-pdf.md) | PDF | `PDF` | `:engine:pdf` | M2 |
| [06](06-pages-and-templates.md) | Pages & Templates | `PAGES` | templates in `:core:model`, page manager UI | M1 |
| [07](07-library.md) | Library & Organization | `LIB` | `:feature:library` | M1 |
| [08](08-design-system.md) | Design System & Motion | `DS` | `:core:designsystem`, `:core:ui`, editor chrome | M1 |
| [09](09-text-and-media.md) | Text & Media | `TEXT`, `MEDIA` | text/image/audio elements | M2 |
| [10](10-recognition-and-search.md) | Recognition & Search | `REC` | `:engine:recognition` | M3 |
| [11](11-quality-and-release.md) | Quality, Performance & Release | `QA` | `.github/`, benchmarks, release | M0 |
| [12](12-smart-board-ai.md) | Smart Board AI ("expert student") | `AI` | `:ai:*`, `:engine:layout` | M2 |

## Suggested team split

| Team size | Assignment |
|---|---|
| 1 person | QA-01 → DS-01/02 → INK → SPEN → CANVAS → DOC, in milestone order |
| 2 people | **A:** INK + SPEN + CANVAS (the "pen" person). **B:** DS + DOC + LIB + PAGES (the "app" person). |
| 3–4 people | A: INK + SPEN · B: CANVAS · C: DOC + LIB + PAGES · D: DS + QA + AI |
| 5+ | One per workstream, starting with the M1 column |

The **pen** path (INK → SPEN → CANVAS) is the product's heart and its critical path. Give it the strongest engineer.

## Task rules
- **Size:** S ≤ 1 day · M ≤ 3 days · L ≤ 1 week · XL needs splitting before starting.
- **One task = one PR.** Branch name: `ws/<prefix>-<nn>-short-name`, e.g. `ws/ink-03-highlighter`.
- **Claiming:** open a GitHub issue with the "Task" template, title `[INK-03] Highlighter`, and assign yourself. Tick the box in the workstream file when the PR merges.
- **Done means** the "Done when" column is satisfied, there are tests where logic is testable, and the change was checked on a real stylus device (see [CONTRIBUTING](../../CONTRIBUTING.md#definition-of-done)).
- **Cross-workstream changes** (for example, changing a type in `:core:model`) need a review from the owner of every affected module.

## Interfaces between workstreams
These contracts let teams work in parallel. Change them only through a PR that every affected owner reviews.

| Contract | Defined in | Producer → Consumer |
|---|---|---|
| `StylusEvent`, `PalmRejection` | `:engine:stylus` | SPEN → CANVAS, INK |
| Brush presets (`PenStyle` → ink `Brush`) | `:engine:ink` | INK → CANVAS, DS (pen picker) |
| `Page`, `PaperTemplate`, `Tool` | `:core:model` | everyone |
| `NotebookStore` (`.bnote` I/O) | `:core:storage` | DOC → editor, library |
| `PdfDocumentSource` | `:engine:pdf` | PDF → CANVAS |
| `HandwritingRecognizer`, `ShapeRecognizer`, `SearchIndex` | `:engine:recognition` | REC → INK, LIB |
| `Motion`, theme tokens | `:core:designsystem` | DS → all UI |
| `PageBlock`, `InkSemantic`, `VectorDiagram` | `:core:model` | AI → CANVAS (renderer), DOC (storage) |
| `VisionProvider`, `AiDocument`, `Prompts` | `:ai:vision-api` | AI → provider modules, editor |
