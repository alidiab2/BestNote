# WS-01 Ink & Brushes (`INK`)

**Goal:** the best-feeling ink on Android. Every pen should feel intentional on an S Pen.
**Owns:** `:engine:ink`
**Built on:** `androidx.ink` (brushes, stroke geometry, rendering). See [ADR-0002](../architecture/adr/0002-ink-engine.md).
**Reference:** [Goodnotes pens research](../research/goodnotes/01-pens-and-drawing-tools.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] INK-01 | Brush presets: Fountain (pressure), Ball (constant width), Brush (high pressure range, tapered) | M1 | M | — | Three pens selectable; side-by-side screenshot reviewed by DS |
| [x] INK-02 | Pencil: graphite texture, tilt widens and softens the stroke | M1 | L | SPEN-05 | Tilting the S Pen visibly shades; texture doesn't swim when zooming |
| [x] INK-03 | Highlighter: translucent, drawn *under* ink, no darkening where strokes overlap | M1 | M | — | Overlapping strokes stay the same tone; ink stays crisp on top |
| [x] INK-04 | Pressure curve per pen + "pressure sensitivity" slider | M1 | S | SPEN-06 | Slider at 0 gives constant width; at max follows the curve in the spec |
| [x] INK-05 | Stroke stabilization (smoothing) setting, 0–100 | M1 | M | — | 100 removes visible hand jitter without lag over 1 frame |
| [x] INK-06 | Color + thickness model, saved custom colors, favorite pen presets | M1 | M | DS-04 | Presets persist; switching preset is one tap |
| [x] INK-07 | Hold-to-straighten line; dashed line option | M2 | M | — | Holding still for 500 ms snaps the stroke to a straight line with a subtle animation |
| [x] INK-08 | Shape snap: circle, ellipse, rectangle, triangle, arrow, line | M2 | L | REC interface | Hold-to-snap shapes; recognition runs off the main thread in < 30 ms |
| [ ] INK-09 | Laser pointer (fading trail, presentation mode) | M3 | S | — | Trail fades over 1 s; never saved to the page |
| [ ] INK-10 | Tip shape options (round / chisel) and tip sharpness | M3 | M | INK-01 | Chisel produces calligraphic variation by orientation |
| [x] INK-11 | Fill for closed shapes | M3 | S | INK-08 | Fill color/opacity editable after drawing |

## Notes
- Brush tuning constants live in one file so designers can tweak them without reading render code.
- Keep a "brush lab" debug screen (debug builds only) showing all pens at several pressures and tilts. Every tuning PR includes a before/after screenshot of it.
