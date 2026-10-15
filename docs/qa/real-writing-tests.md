# Real handwriting tests (QA-INK)

`adb input swipe` draws straight finger lines with no pressure, tilt or timing. It says nothing about
how ink looks when someone actually writes. This harness replays **realistic handwriting** (words,
joined cursive, Arabic, math, tiny and large writing, dots, crossings, drawings, shading) as real
stylus `MotionEvent`s, with pressure, tilt, orientation, 240 Hz timing and per-frame batching.

## Pieces

| Piece | Where | What |
|---|---|---|
| Generator | `tools/handwriting/` (Python 3) | Text and shapes → stroke files (`*.json`) + PNG previews |
| Corpus | `tools/handwriting/corpus/` | Committed stroke files and `previews/*.png` |
| Replay | `app/src/debug/kotlin/app/bestnote/debug/replay/` | Debug-only receiver that feeds a stroke file into `PageCanvasView` |
| Script | `scripts/agent/replay.sh` | Push, trigger, wait for "done", optional screenshot |

### Generator
- **Latin**: Hershey script (cursive) and Roman simplex (print) single-stroke fonts, public domain
  ([licence](../../tools/handwriting/fonts/hershey/LICENSE-HERSHEY.txt)). Per-glyph scale, rotation and
  baseline jitter, per-word slant, baseline drift per line, smooth tremor. Lowercase letters in a word
  are joined into one pen-down stroke; i/j dots, t crossbars and the second stroke of x come after the
  word. Formulas use print style with stacked fractions (`{dy/dx}`) and superscripts (`^2`).
- **Arabic**: shaped with HarfBuzz from Aref Ruqaa (OFL, [licence](../../tools/handwriting/fonts/arefruqaa/OFL.txt)),
  rasterized, split into components, skeletonized with scikit-image and walked into pen paths from the
  right end. Letter bodies are written right to left; dots and diacritics follow as short strokes.
  Solid letter heads (و ق ف م ط ة) are given back a small loop. Known limits: a few heads (initial ق,
  م in مع) still come out as hooks, and one false loop appears under ع.
- **Dynamics**: speed follows the two-thirds power law (slow on curves, near-stop at cusps, faster on
  straights) with limited acceleration; pressure ramps in over ~20–35 ms, lightens with speed, fades
  out over ~25–40 ms; tilt 35–55° drifting slowly; orientation drifts around the hand angle; pauses
  between letters, words and lines; samples on a continuous 240 Hz clock with sub-pixel noise.
- Deterministic (fixed seeds). The Smart Board renderer can reuse `hw/latin.py`, `hw/arabic.py` and
  `hw/dynamics.py` to emit handwriting-looking ink.

### Stroke file format (v1)
```json
{"format":"bestnote-strokes","version":1,"units":"mm","sampleRateHz":240,"name":"…","width":180,"height":82,
 "durationMs":131200,"writer":{…},
 "strokes":[{"type":"ink","button":false,"samples":[{"x":2.1,"y":7.0,"t_ms":300.0,"pressure":0.05,"tiltRad":0.79,"orientationRad":2.35},…]},
            {"type":"hover","button":true,"samples":[…]}]}
```
`ink` = pen down (DOWN/MOVE/UP), `hover` = pen in range (HOVER_ENTER/MOVE/EXIT, pressure 0).
`button` = S Pen side button held (`BUTTON_STYLUS_PRIMARY`). x/y in mm from the page's top-left,
`t_ms` absolute and increasing. Tilt and orientation use Android's `AXIS_TILT` / `AXIS_ORIENTATION`.

### Replay
`ReplayInstaller` (a debug-manifest `ContentProvider`) listens for `app.bestnote.debug.REPLAY` while an
activity is resumed, finds the on-screen `PageCanvasView`, sets the pen, and `StrokeReplayer` dispatches
`TOOL_TYPE_STYLUS` events (source stylus|touchscreen) through `dispatchTouchEvent` /
`dispatchGenericMotionEvent` on the main thread at the recorded timing. Samples are batched per 120 Hz
frame as historical samples, so stylus mapping, palm rules, prediction, wet/dry ink, the highlighter,
hover cursor and the side-button eraser all run as with a real pen. 1 mm in the file = 1 mm on screen
(`xdpi`), shrunk to fit the canvas. Nothing of this is in `src/main` or the release build.

## Run it
```bash
# once: Python env for the generator (gitignored)
python3 -m venv tools/handwriting/.venv
tools/handwriting/.venv/bin/pip install -r tools/handwriting/requirements.txt
# regenerate corpus + previews (deterministic)
tools/handwriting/.venv/bin/python -I tools/handwriting/generate.py
# your own text
tools/handwriting/.venv/bin/python -I tools/handwriting/generate.py --text "Hello there" --xh 4 --out /tmp/hello.json

# build + install a debug APK side by side with other agents' builds, open a notebook in it
./gradlew :app:assembleDebug -PdebugIdSuffix=.qaink
# replay (waits until done), screenshot taken while still holding the device lock
PKG=app.bestnote.debug.qaink SHOT=lecture-fountain \
  scripts/agent/replay.sh tools/handwriting/corpus/lecture_en.json fountain "" --ez clear true
scripts/agent/replay.sh tools/handwriting/corpus/highlight_lecture.json highlighter   # over the text
```
Pens: `fountain`, `ballpoint`, `brush`, `pencil`, `highlighter`. Extras: `--ez clear true`,
`--ef size 8` (px), `--ef batch 0` (one event per sample, i.e. unbuffered), `--ef slow 4` (4× slower),
`--ef scale 0.8`, `--es side none`. Status is in `files/replay/status`, logs under tag `BestNoteReplay`.
`replay.sh` refuses non-emulator devices.

## Corpus
| File | Content |
|---|---|
| `lecture_en` | "Lecture 7 — Kinematics. Motion with constant acceleration…" paragraph, joined cursive, x-height 3.2 mm, 131 s |
| `formulas` | v = u + at, s = ut + ½at², dy/dx = dy/du · du/dx (stacked fractions) |
| `arabic` | قانون نيوتن الثاني / التسارع يتناسب طردياً مع القوة |
| `tiny_fast` | margin notes, x-height 1.5 mm, up to 170 mm/s |
| `slow_large` | "Newton's law / F = ma", x-height 9 mm, slow |
| `drawings` | circles, arrows, a box with a label, graph axes with ticks and a curve |
| `scribble` | fast back-and-forth shading in a box and a circle |
| `highlight_lecture` | three highlighter passes over lines 1–3 of `lecture_en` |
| `hover_erase` | hover between strokes, long hovers, then a side-button erase of "wrong" |

## Defects found (first run, emulator AuraTablet, 2560×1600 @ 320 dpi)
Every corpus file was replayed through fountain, ballpoint, brush and pencil (default sizes and colours),
plus highlighter passes over written text and the hover + side-button erase file. Full screenshots are in
`/tmp/bestnote-shots/qaink-<pen>-<file>.png` on the dev Mac; crops are in [`real-writing/`](real-writing/).
Engine code was **not** changed; these are for the INK / CANVAS owners. Most severe first.

| # | Defect | Pens | Evidence |
|---|---|---|---|
| D1 | **Wet highlighter is drawn on top of the ink.** While the highlighter stroke is in progress it tints the black letters olive and stacks darker over earlier highlight; on pen-up it "pops" under the ink and gets lighter. Visible flicker of colour and z-order on every highlight. | highlighter | [`d1-highlighter-wet-over-ink.png`](real-writing/d1-highlighter-wet-over-ink.png) vs dry [`d1-highlighter-dry.png`](real-writing/d1-highlighter-dry.png); full: `qaink-highlighter-wet.png`, `qaink-highlighter-over-text.png` |
| D2 | **Dots vanish.** A real 40–75 ms tap (i/j dots, the `·` in dy/du · du/dx, Arabic dots on ن ب ي) renders as a 1–2 px speck with fountain, ballpoint and pencil, and **disappears completely with brush**. One fountain i-dot rendered as a hollow ring. Arabic is unreadable without its dots. | all | [`d2-dots-fountain-arabic.png`](real-writing/d2-dots-fountain-arabic.png), [`d2-dots-brush-arabic.png`](real-writing/d2-dots-brush-arabic.png), [`d2-middot-fountain.png`](real-writing/d2-middot-fountain.png), [`d2-dot-ring-fountain.png`](real-writing/d2-dot-ring-fountain.png); full: `qaink-*-arabic.png`, `qaink-fountain-formulas.png` |
| D3 | **Curves are faceted.** Slow, large writing and hand-drawn circles show visible straight segments and corners (N, e, o loops, circle lower right). The source paths are smooth (see `previews/slow_large.png`, `previews/drawings.png`). | all | [`d3-facets-slow-large-ballpoint.png`](real-writing/d3-facets-slow-large-ballpoint.png), [`d3-facets-circle-pencil.png`](real-writing/d3-facets-circle-pencil.png); full: `qaink-*-slow_large.png`, `qaink-*-drawings.png` |
| D4 | **Brush is unusable for normal handwriting at its default size.** Counters of e/a/o fill in, 1.5 mm notes become black blobs, bulges at cusps (w, n joins), comma-shaped blob where strokes start with a hook. | brush | [`d4-brush-lecture.png`](real-writing/d4-brush-lecture.png), [`d4-brush-tiny.png`](real-writing/d4-brush-tiny.png); full: `qaink-brush-*.png` |
| D5 | **Fountain pen shows no pressure or speed response and no taper.** Pressure ramps 0.05 → 0.55 → 0.1 on every stroke, yet lines are monoline with blunt round caps: it looks like a felt marker, not a fountain pen. No thick/thin from nib angle (tilt/orientation unused). | fountain | [`d5-fountain-monoline.png`](real-writing/d5-fountain-monoline.png); full: `qaink-fountain-lecture_en.png` |
| D6 | Small knobs at cusps and direction reversals (bottom of h and p stems, n arches) where the pen nearly stops. | fountain, brush | `qaink-fountain-lecture_en.png`, `qaink-brush-slow_large.png` |
| D7 | Pencil is flat grey with no grain and no tilt shading; at 1.5 mm writing it is faint and thin. | pencil | [`d7-pencil-tiny.png`](real-writing/d7-pencil-tiny.png); full: `qaink-pencil-*.png` |
| D8 | Highlighter default width covers only ~⅓ of the x-height and has round caps (no chisel), so a pass under a line reads as an underline, not a highlight. | highlighter | `qaink-highlighter-over-text.png` |
| D9 | Hover cursor with the side button held (eraser) is a grey disc almost identical to the pen cursor. The erase itself works: holding the button erased "wrong" and returned to the pen. | hover | [`d9-hover-pen.png`](real-writing/d9-hover-pen.png), [`d9-hover-button-eraser.png`](real-writing/d9-hover-button-eraser.png); full: `qaink-hover-erase-end.png` |

**Not seen:** broken Arabic joins (connected letters stay connected), blobs at stroke *start*, prediction
overshoot tails, dropped samples (the stylus log reports 238–240 Hz per stroke).

**Other observations:** strokes are not kept when the app process dies (expected until DOC-02 autosave).
The hover cursor tracks the pen tip and disappears on exit.

**Harness limits:** `MotionEvent.obtain`/`addBatch` take whole milliseconds, so 240 Hz timestamps alternate
4/4/4/5 ms (real digitizers have sub-ms timestamps). The emulator has no real front buffer, so this does not
measure pen-to-pixel latency (QA-04 does). The Arabic generator still renders initial ق and م in مع as hooks.
