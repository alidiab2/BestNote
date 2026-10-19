# Board figures: where the quality goes, and what fixes it (REQ-001, round 2)

Owner's report: alidiab2/BestNote-requests#2 ("الرسم مش دقيق"). The 8 attachments are 4 board photos
(2, 3, 6, 8) and the Smart Board pages made from them (1, 4, 5, 7): 7 drawings in all.

## Bench

`:ai:board-scan` tests, `bench/` package (outputs in `ai/board-scan/build/bench/`, nothing in the repo):

- `BenchBoards`: the boards as the app sees them. Board 2 is a raw photo, cropped to the board, flattened
  and auto-enhanced like `BoardScan.flatten`. The detector misses this board because a student stands in
  front of it, so the corners are set by hand. Boards 3, 6 and 8 are the in-app viewer's screenshots, cropped.
- `FigureBench`: hand-annotated ground truth for the 7 drawings (box and labels, in board pixels). The
  pipelines run on top of it. Each drawing is scored against the photo:
  - **f1**: Chamfer-style recall/precision between the photo's ink centre lines and the redraw's strokes.
    The labels' writing is left out. Tolerance is 2.5 % of the drawing's diagonal.
  - **fine f1**: the same at a third of that tolerance.
  - **kinks**: corners in the redraw where the photo's line runs smooth (a curve drawn as straight pieces).
  - **labels**: typeset labels found among the expected ones; **placed**: those within about a letter
    of where the teacher wrote them.
  - **extra**: labels nobody wrote (made-up ticks).
- `FigureBenchTest`: every experiment, switched on cumulatively, on 5 pipelines:
  - **oracle**: ground-truth boxes and labels, so the CV alone is measured as if the model were perfect;
  - **app** on the Sonnet 5.5 answer (the owner's model) and **app** on the Opus 5.5 answer (the app's default);
  - **redraw** on Opus' boxes with its library parameters removed;
  - **library** on Opus' parameters only.
  It writes `build/bench/report.md` and a photo | redraw picture for every drawing.
- `FigureFidelityTest`: the regression gate. Thresholds sit just under the numbers reached below.

Live answers, recorded as fixtures in `resources/bench/`: 3 real calls, which used up the remaining budget
(25/25). The boards were stacked two per photo to save calls:
- Opus 5.5 on boards 2+8;
- Opus 5.5 on boards 3+6;
- Sonnet 5.5 on boards 2+8.

## Diagnosis: where each drawing loses quality

| drawing (photo → page) | (a) model answer | (b) photo → vectors (`:ai:board-scan`) | (c) renderer / layout |
|---|---|---|---|
| U(x) graph (2 → 1) | Sonnet: box + labels only (`k=graph`), no "7". Opus: `plot` with 14 guessed points (`c=0.6`), invented ticks | the smooth curve became straight pieces (13 false corners over the bench); dotted drops partly lost | library `plot`: straight polyline through the points, curve cut at x = 2.5, numeric ticks and "(2,−22)" labels the teacher never wrote |
| F–Δt graph (3 → 4) | Opus: `plot f=1, shade 0..1` (`c=0.7`); its two Δt labels are ~80 px low | the pale blue level and the pale red hatching are under the ink threshold. The hatching's colour is almost gone in a 1600 px JPEG (red−green ≈ 10, about the noise) | library: 0.2…1 ticks on both axes (11 extra labels), solid hatch |
| block + v + F (6 → 5) | Opus: `blocks` (`c=0.8`); "v" and "m" given at the same x | the v arrow over the block merged into the block outline; a misplaced "F" was redrawn as strokes | library: idealised block (fine, but not the teacher's proportions) |
| block + v (6 → 5) | Opus: `blocks` | good | — |
| m₁\|m₂ with F₂,₁ / F₁,₂ (8 → 7) | labels ±15 px; Sonnet's box leaves out the ground | the shaft is cut by the letters (dashed bits inside the blocks); the ground is lost when the box is tight | — |
| before collision (8 → 7) | **Opus: box read as pixels** (on this busy board the pixel reading landed on writing), so the drawing was lost | the Hebrew caption at the ground's end looked like an arrowhead | — |
| after collision (8 → 7) | — | one block fitted as a quarter circle; the arrow over "u₂" left as strokes | — |

So the model is right about *what* is drawn (Opus even returns library kinds) but not precise about *where*
(VLMs are weak at exact positions; see "Vision language models are blind", arXiv:2407.06581). The photo has
the geometry. The biggest losses were:
1. the app trusting the library's idealised drawing over the photo whenever `c ≥ 0.5`;
2. a box/label unit heuristic that misfires on busy boards;
3. the fitter's all-or-nothing curve test.

## Research (approaches and their cost)

| approach | what it is | quality for teacher sketches | cost | verdict |
|---|---|---|---|---|
| Whiteboard clean-up: white balance + background subtraction (Zhang & He, *Whiteboard scanning and image enhancement*, 2007), adaptive threshold, colour distance in a perceptual space for pale markers | `Enhance` + `InkMask` already do this; the PR #82 faint-line pass adds long thin faint strokes | needed, but cannot recover colour the JPEG already removed | 0 tokens | keep; feed it **full-resolution** crops (below) |
| Centre-line vectorisation: skeleton + graph (Zhang–Suen), Potrace-style corner detection and curve optimisation (Selinger 2003), Schneider's piecewise Bézier fitting | what `Skeleton` + `FigureFit` do; the missing piece was splitting at real corners and fitting each run as a line **or** a curve | high for line drawings | 0 tokens | **done (E3)** |
| Primitive fitting: RDP, Hough for lines/circles, dashed/dotted grouping, hatching detection | in place (RDP, circle fit, dashes, dots, hatching); learned vectorisers exist (Egiazarian et al., *Deep Vectorization of Technical Drawings*, arXiv:2003.05471) | learned models would be better but are heavy native/ML deps | 0 tokens, large binary | keep the analytic one |
| Hybrid: model gives a semantic scene graph (axes, curve, blocks, arrows, labels), local CV refines the geometry | the library kinds already give the semantics; the geometry must come from the photo | best long-term | 0 extra tokens (catalogue ≈ 755 tokens already sent) | **first stage done (E2)**: photo first, library only as fallback; next: constrain the fit with the kind |
| Second request: a high-resolution crop per figure | the model reads labels and positions on a crop | better labels and positions | +1 call per figure (≈ +1–1.5k tokens each) | not now (budget); a local full-res crop gets most of it for free |
| Model writes SVG/TikZ directly (e.g. StarVector, arXiv:2312.11556) | the model draws | semantic but not faithful: general VLMs place geometry poorly (BlindTest) | large output | no |

## Experiments (cumulative; each cell is mean f1 / fine f1 / kinks / labels found / labels placed / extra labels)

| idea | oracle | app, Sonnet answer | app, Opus answer | cost | decision |
|---|---|---|---|---|---|
| baseline (after PR #82) | 0.937 / 0.826 / 13 / 1.00 / 1.00 / 0 | 0.875 / 0.816 / 13 / 0.94 / 0.56 / 0 | 0.512 / 0.181 / 7 / 0.67 / 0.00 / 22 | — | — |
| E1 box units decided once per answer (pixels only if that reading puts more labels on ink over all drawings) | = | = | 0.614 (redraw path 0.779 → 0.909) | 0 | **keep** |
| E2 photo first, library as fallback | = | = | **0.909** / 0.769 / 7 / 0.96 / 0.74 / 2 | 0 | **keep** |
| E3 split at the pen's corners, each run a line or a smooth curve | 0.936 / 0.819 / **6** | 0.874 / 0.797 / **3** | = | 0 | **keep** (kinks −54 %, f1 flat) |
| E4 a flat box (one side > 1.5× the other) padded by its long side | **0.949** | **0.889** | **0.944** | 0 | **keep** (squarish boxes keep 8 % per side: the incline's angle marks need it) |
| E5 a label on a drawn line snaps to its word (≤ 12 % of the crop) | = | placed 0.56 → **0.72** | = | 0 | **keep** |
| E6 arrowheads as long as drawn; long lines need a real head | **0.951** / 0.826 / 6 | **0.894** / 0.769 / 5 | **0.946** / 0.802 / 6 / 0.96 / 0.70 / 2 | 0 | **keep** |
| E7 no large arc through a drawn corner (block fitted as a quarter circle) | = | = | = | 0 | rejected: no gain, and it broke the incline's angle marks |
| Faint colour by local colour distance (hatching) | — | — | — | 0 | rejected for now: in the attached 1600 px JPEG the hatching sits at noise level (99.7th percentile of colour deviation 6.2, vs 3.0 on empty board) |

Net effect on the owner's model (Sonnet): f1 0.875 → 0.894, false corners 13 → 5, labels where the teacher
wrote them 0.56 → 0.72. On the app's default model (Opus): f1 0.512 → 0.946, labels found 0.67 → 0.96,
made-up labels 22 → 2, and no drawing is lost.

## What is left for excellent quality (next PRs, in order)

1. **Redraw from the full-resolution photo.** The CV gets the same 1600 px bitmap the model sees.
   Flattening the camera image (3–4× the pixels) for the figure crops costs no tokens. It is the only way
   to recover pale markers and hatching, and it makes dots and arrowheads reliable. `BoardFigures.resolve`
   would take the full-res flattened board; the boxes are fractions, so nothing else changes.
2. **Kind-guided fitting (hybrid stage 2).** When the model says `blocks` / `plot` / `fbd`, fit that
   structure to the ink instead of free primitives. For `blocks`: N rectangles at the mass labels, a
   ground line, arrows at the force/velocity labels. For `plot`: axes plus one smooth curve plus guides.
   This fixes the topology errors the f1 cannot see: the v arrow merged into a block, shafts cut into
   dashes by letters, letters left as strokes.
3. **Label ↔ word matching by text, not only distance.** Match the label's length and characters
   against the word blobs (a global assignment instead of greedy nearest). "Placed" is still 0.70–0.72.
4. **Ground truth for faint ink.** The bench's reference uses the same ink detector, so lost pale strokes
   are not penalised. Add hand-masked references for boards 3 and 6.
5. More boards in the bench (only 7 drawings now): any new owner report with photos should be added to
   `FigureBench.truths`.
