# WS-09 Text & Media (`TEXT`, `MEDIA`)

**Goal:** mix typed text, images and audio with handwriting without breaking the pen flow.
**Owns:** page element types (text box, image, audio) across `:core:model`, `:engine:canvas`, `:feature:editor`
**Reference:** [text research](../research/goodnotes/03-handwriting-and-text.md), [media research](../research/goodnotes/07-media-and-elements.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] TEXT-01 | Text box element: insert, edit, move, resize | M2 | M | CANVAS-08 | — |
| [x] TEXT-02 | Formatting: font, size, color, bold/italic/underline, alignment, lists | M2 | M | TEXT-01 | Arabic text shapes and aligns correctly |
| [ ] TEXT-03 | Typed text document type | M4 | L | TEXT-02 | — |
| [x] TEXT-04 | Keyboard typing and design: tap-to-type on the canvas (in place, IME/bidi), format bar above the keyboard, paragraph styles (Title…Code), Text shortcuts + Markdown autoformat, snap guides + arrow nudging, caret kept above the keyboard | M2 | L | TEXT-02, SPEN-10 | Paragraph styles persist in `.bnote` v9; v8 files still read |
| [x] TEXT-05 | Many fonts: bundled OFL text fonts (Instrument Sans, Fraunces, Lora, JetBrains Mono, IBM Plex Sans Arabic, Noto Naskh Arabic, Heebo, Frank Ruhl Libre; ~1 MB of variable fonts with real bold) plus ~60 verified OFL Google Fonts families downloadable on demand (Arabic, Hebrew, Latin sans/serif/mono/hand); picker with search, script filter, previews in the font, favourites and recents, used by text boxes, the keyboard format bar and the lasso text bar; font id stored per text box, a missing font falls back to the same kind (serif/mono/hand/sans) on screen and in PDF | M3 | M | TEXT-02 | Arabic and Hebrew text boxes switch fonts and export to PDF in that font |
| [x] MATH-01 | Handwriting → equations: lasso → "Convert to equation" (AI provider, strict LaTeX prompt, `LatexSanitizer`; `EquationRecognizer` seam for on-device AI-10), `EquationElement` typeset by `:engine:mathtype` (TeX spacing/styles on the STIX Two Math MATH table, OFL; vector glyph outlines on canvas and in PDF), editor sheet (live preview, structure palette, LaTeX field with error highlight, rewrite by hand, copy LaTeX) | M2 | L | TEXT-01, REC-05 | Equations persist in `.bnote` v10 (v9 files still read; locked notebooks keep them); typesetting unit-tested |
| [x] MATH-02 | LaTeX coverage + equation style: `\cancel`/`\bcancel`/`\xcancel`/`\cancelto`, `\underbrace`/`\overbrace` with labels, `\boxed`, `\underline`/`\overline`, `\vec`/`\hat`, RTL `\text` as one BiDi run, unknown commands never printed as text (argument drawn, logged); in both the typesetter and the Smart Board hand. "Equation style: Handwritten / Typeset (LaTeX)" per template (TPL Look), per notebook (Smart Board chips) and per equation (selection menu); `LatexSanitizer` keeps/repairs the commands | M2 | M | MATH-01, TPL-02 | Unit tests per command + fallback; equations persist their style in `.bnote` |
| [x] MEDIA-01 | Insert image: picker, camera, paste, drag-and-drop | M2 | M | — | Large images downsampled and stored once per notebook |
| [x] MEDIA-02 | Crop, rotate, resize image; lock image | M2 | M | MEDIA-01 | — |
| [ ] MEDIA-03 | Local sticker packs (files, importable) | M3 | M | MEDIA-01 | — |
| [x] MEDIA-04 | Audio recording in a notebook (foreground service, background-safe) | M3 | L | — | — |
| [x] MEDIA-05 | Audio ↔ ink sync: tap a stroke to play from that moment | M3 | L | MEDIA-04 | Strokes carry recording timestamps |
| [ ] MEDIA-06 | Document scan to page (edge detect, perspective fix) | M4 | L | — | Must work without Play Services in the F-Droid build |
