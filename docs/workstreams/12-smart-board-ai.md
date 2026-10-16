# WS-12 Smart Board AI (`AI`)

**Goal:** photograph a whiteboard and get the notes the best student in the room would have written. That means logical order, ruler-straight diagrams, meaningful colors and clean LaTeX, not an OCR dump.
**Owns:** `:ai:vision-api`, `:ai:math`, `:engine:layout`, future provider modules (`:ai:vision-gemini`, …)
**Decision:** [ADR-0007 Optional cloud AI](../architecture/adr/0007-optional-cloud-ai.md)

## The mental model: "the expert student note-taker"
The model is told it is an elite, meticulous student copying the board into a notebook (`Prompts.EXPERT_STUDENT`). Each of the four behaviors is also enforced **deterministically** in `:engine:layout`, so notes stay clean even when the model is only partly right:

| Behavior | In the prompt | Enforced by |
|---|---|---|
| **Lecture flow over board layout:** copy in teaching order, with a hierarchy of title → definitions → proofs → key formulas → margin notes | `order`, block types `definition`, `proof`, `margin_note`, `boxed` | `LectureFlow` (explicit order, else panel-by-panel reading, RTL aware; margin notes follow their anchor), `BlockFlow` (headings/definitions keep-with-next, margin notes aligned beside their block) |
| **Ruler and compass geometry** | "lines straight, angles exact, arrows anchored" | `RulerGeometry`: weld endpoints, square H/V, snap 45°/15°, close polygons, round circles, anchor arrows on outlines, half-point grid |
| **Color-coded semantics** | `ink_color` + `semantic` (regular/essential/annotation/emphasis) | `ColorSemantics` (names + hue), `NotebookPalette` (one coherent palette; essential formulas boxed) |
| **Self-correction and polish** | fix slips, bullets, LaTeX, keep the lecture's language | `LatexSanitizer` (delimiters, Unicode → LaTeX, safety, balance), `DocumentMapper` (drops invalid blocks, never fails the whole page) |

## Tasks

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] AI-01 | Expert-student system prompt + provider-neutral schema | M2 | M | — | `PromptsTest` green |
| [x] AI-02 | `LectureFlow`, margin/keep-with-next layout, `RulerGeometry`, `ColorSemantics` | M2 | L | — | `:engine:layout` tests green |
| [x] AI-03 | Gemini provider (BYOK, key in Android Keystore, JSON mode + response schema) in `:ai:vision-gemini` | M2 | M | ADR-0007 | Port from AuraNotes; MockWebServer tests |
| [ ] AI-04 | Board capture: camera + document-edge crop + perspective fix | M2 | L | MEDIA-06 | Works without Play Services in `foss` |
| [x] AI-05 | **Handwritten output.** Render `PageBlock`s as real, editable ink strokes on notebook paper, as if the user wrote them neatly: text and math in a handwriting style (default neat hand; later learned from the user's own strokes), hand-underlined titles, pen-drawn boxes around key formulas, ruler-straight diagrams in ink, green margin notes with an arrow. No typeset cards, no markdown look. The page "writes itself" fast (≈3 s for a full page, left→right per line, lines drawn in order). | M2 | XL | DS-01, CANVAS-06, INK-01 | Matches design board v2 (Smart Board screen). Every output stroke is erasable and lasso-selectable like the user's own ink |
| [x] AI-06 | Real `BlockMeasurer` (text + LaTeX aware) | M2 | M | AI-05 | Layout has no overlaps on the evaluation set |
| [x] AI-07 | Review sheet: preview the reconstruction, pick template, insert as new pages (one undo step) | M2 | M | AI-05 | — |
| [ ] AI-08 | Beautify selection (lasso → tidy) | M3 | M | CANVAS-08, AI-05 | — |
| [ ] AI-09 | Evaluation set: 50 real board photos (math, physics, CS; Arabic + English) with expected structure; score order, geometry, color, LaTeX | M2 | L | — | Score report committed; run before any prompt change |
| [ ] AI-10 | On-device fallback (no network): local layout + LaTeX OCR model | M4 | XL | REC-01 | Split before starting |
| [x] AI-11 | Real-photo fix (schema requires `type`/`order`/`text`, forgiving parser, salvaging mapper, drop reasons); Claude, OpenAI and OpenAI-compatible providers (`:ai:vision-claude`, `:ai:vision-openai`, `:ai:vision-providers`); live model lists with automatic Best/Fast; debug log; "Convert with AI" for REC-05 | M2 | L | AI-03, REC-05 | Recorded-answer tests green; Settings → Smart Board AI and Debug on the emulator |

## Status (AI-04..07 first cut)
- `:engine:handwriting` writes blocks as timed ink: Hershey print/script hands, Aref Ruqaa forms for Arabic, LaTeX subset (fractions, scripts, roots, Greek, operators), pen-drawn boxes, ruler diagrams, green margin notes with an arrow. `PageComposer.measurer` is the AI-06 measurer.
- `RuledFlow` + `RuledPaper.of(template, size)` put every line of writing on the editor's own ruled lines, inside the margin.
- `:feature:smartboard`: photo (camera or picker) → crop → provider (Gemini with the user's key, else the offline `DemoVisionProvider`) → review page that writes itself → inserted as new pages, one undo step.
- AI-11: any provider answer is parsed by `AiDocumentParser` (fences, prose, truncation, bad escapes, aliases) and mapped by `DocumentMapper`, which salvages text from alternate fields and records one reason per dropped block (`VisionDiagnostics`, Settings → Debug). Models are never hard-wired: `ModelCatalog` picks the newest pro-class (Best) or fast model from each provider's live list.
- Left for later: AI-04 edge detection + perspective fix, template choice in review, Cornell/cheat-sheet paper, lasso (CANVAS-08), the user's own hand ("Mine").

## Output style (agreed with the maintainer)
The result must look like the user wrote it in their own notebook, only very neat and very fast. It must never look like rendered Markdown or typeset documents. See `design/` → Smart Board.

## Rules
- Any prompt change must keep `PromptsTest` green and be scored on the AI-09 evaluation set.
- Board colors never reach the page directly. They always go through `InkSemantic` → `NotebookPalette`.
- Never fail a whole reconstruction because of one bad block. Drop it and count it.
