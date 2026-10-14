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
| [ ] AI-03 | Gemini provider (BYOK, key in Android Keystore, JSON mode + response schema) in `:ai:vision-gemini` | M2 | M | ADR-0007 | Port from AuraNotes; MockWebServer tests |
| [ ] AI-04 | Board capture: camera + document-edge crop + perspective fix | M2 | L | MEDIA-06 | Works without Play Services in `foss` |
| [ ] AI-05 | Renderer for `PageBlock`s on the canvas (heading, callout cards, boxed math, proof, margin note, vector diagram) | M2 | L | DS-01, CANVAS-06 | Matches the design spec in light/dark |
| [ ] AI-06 | Real `BlockMeasurer` (text + LaTeX aware) | M2 | M | AI-05 | Layout has no overlaps on the evaluation set |
| [ ] AI-07 | Review sheet: preview the reconstruction, pick template, insert as new pages (one undo step) | M2 | M | AI-05 | — |
| [ ] AI-08 | Beautify selection (lasso → tidy) | M3 | M | CANVAS-08, AI-05 | — |
| [ ] AI-09 | Evaluation set: 50 real board photos (math, physics, CS; Arabic + English) with expected structure; score order, geometry, color, LaTeX | M2 | L | — | Score report committed; run before any prompt change |
| [ ] AI-10 | On-device fallback (no network): local layout + LaTeX OCR model | M4 | XL | REC-01 | Split before starting |

## Rules
- Any prompt change must keep `PromptsTest` green and be scored on the AI-09 evaluation set.
- Board colors never reach the page directly. They always go through `InkSemantic` → `NotebookPalette`.
- Never fail a whole reconstruction because of one bad block. Drop it and count it.
