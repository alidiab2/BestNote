# WS-10 Recognition & Search (`REC`)

**Goal:** search your handwriting in Arabic and English, entirely on device.
**Owns:** `:engine:recognition` (provider-neutral interfaces + engines)
**Decision first:** [ADR-0006 Recognition](../architecture/adr/0006-recognition.md)
**Reference:** [smart features research](../research/goodnotes/04-ai-features.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [ ] REC-01 | Engine evaluation (ML Kit Digital Ink vs open models) on an Arabic + English test corpus | M2 | L | — | Accuracy/latency table committed; ADR-0006 accepted |
| [ ] REC-02 | Background indexing: recognize pages after edits (WorkManager, idle, charging-aware) | M3 | M | REC-01, DOC-02 | Index stays current without visible battery cost |
| [ ] REC-03 | Global search UI: handwriting + typed + PDF text, results highlighted on the page | M3 | L | REC-02 | Tap a result → page opens zoomed to the match |
| [ ] REC-04 | Search inside images (on-device OCR) | M3 | M | REC-01 | — |
| [ ] REC-05 | Convert handwriting → text (lasso → "Convert") | M4 | M | REC-01 | — |
| [ ] REC-06 | Handwriting reflow and alignment | M4 | XL | REC-05 | Split before starting |
| [ ] REC-07 | Handwriting spellcheck (underline + suggestion in the user's handwriting) | M4 | XL | REC-05 | — |
| [ ] REC-08 | Math Assist: write `=` after an expression to get the result | M4 | L | REC-01 | — |
| [x] REC-09 | `ShapeRecognizer` implementation for INK-08 | M2 | M | — | Geometric fit, no ML needed |
