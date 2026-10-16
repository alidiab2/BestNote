# WS-02 S Pen, Input & Samsung (`SPEN`)

**Goal:** the best S Pen experience on Android outside Samsung Notes, and better than Samsung Notes where we can be.
**Owns:** `:engine:stylus`, window/multi-instance/keyboard code in `:app`
**Spec:** [`../platform/samsung-s-pen.md`](../platform/samsung-s-pen.md). Read it first.

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] SPEN-01 | Event pipeline: `MotionEvent` → `StylusEvent` incl. historical samples, `requestUnbufferedDispatch` | M1 | M | — | Logged sample rate matches the digitizer (≈240 Hz on Tab S) |
| [x] SPEN-02 | Palm rejection: auto stylus-only mode on first pen touch; finger pans and zooms; cancel palm strokes | M1 | L | SPEN-01 | Resting a palm while writing for 10 min leaves zero stray marks |
| [x] SPEN-03 | Hover: brush-size cursor / eraser circle preview; hide the system hover dot | M1 | M | SPEN-01 | Cursor tracks the tip with no lag; disappears on exit |
| [x] SPEN-04 | Side button: hold for temporary tool (default eraser; configurable: lasso, highlighter, none) | M1 | M | SPEN-01 | Press, erase, release returns to the previous pen. Works on every device in the matrix |
| [ ] SPEN-05 | Tilt + orientation available to brushes | M1 | S | SPEN-01 | Values verified against the device matrix; devices without tilt degrade gracefully |
| [ ] SPEN-06 | Pressure normalization per device/stylus (S Pen, Wacom EMR, USI, generic) | M1 | M | SPEN-01 | Light, medium and heavy strokes look the same across the matrix |
| [ ] SPEN-07 | Stylus handwriting into text fields (search, rename) | M2 | M | — | Writing in the search box with the S Pen inserts text |
| [ ] SPEN-08 | Multi-window and multi-instance: two notebooks side by side, drag content between them | M2 | L | CANVAS-11 | Split screen with two editors; lasso-drag copies across |
| [ ] SPEN-09 | DeX / mouse / trackpad: pointer icons, right-click menu, Ctrl+scroll zoom | M2 | M | — | Full editing session in DeX without touching the screen |
| [x] SPEN-10 | Keyboard shortcuts (Book Cover Keyboard) + shortcut help overlay (hold Meta) | M2 | M | — | Undo, redo, tools, zoom, new page, search all have shortcuts |
| [ ] SPEN-11 | Air actions via S Pen Remote SDK, `samsung` build flavor only | M4 | L | D4 | Button click = next page / undo (configurable) in presentation mode |
| [ ] SPEN-12 | Foldables: Flex mode layout (tools on the bottom half) | M3 | M | DS-05 | Fold 6/7 half-open shows the canvas top and tools bottom |
| [ ] SPEN-13 | Device matrix test pass + latency measurement report | M1 | M | QA-04 | Report committed to `docs/platform/reports/` |
| [x] SPEN-14 | Quick palette: side-button click while hovering opens a radial pen/color/size palette at the tip (double-click = undo, opt-in); setting `HoverButtonAction` | M1 | M | SPEN-04 | Click in the air opens it; button held while writing stays the temporary eraser. **[verify]** on a Tab: Samsung Air Command may claim the hover click |
