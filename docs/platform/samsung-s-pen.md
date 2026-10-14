# Samsung S Pen & Galaxy Tablets: Platform Spec

S Pen support is BestNote's main selling point. This file is the contract for WS-02. Lines marked **[verify]** come from general knowledge and must be confirmed on a real device before we build on them. Record results in `docs/platform/reports/`.

## 1. Input pipeline

| Topic | Rule |
|---|---|
| Tool type | `MotionEvent.getToolType()`: `TOOL_TYPE_STYLUS` writes. `TOOL_TYPE_ERASER` (styluses with an eraser end) always erases. `TOOL_TYPE_FINGER` pans and zooms in stylus-only mode. `TOOL_TYPE_MOUSE` follows DeX rules (§5). |
| Sampling | Call `View.requestUnbufferedDispatch(event)` on stylus `ACTION_DOWN` so we get every digitizer sample instead of one per vsync. Always consume `getHistoricalX/Y/Pressure/AxisValue` too. |
| Latency | Wet ink goes through `androidx.ink` `InProgressStrokesView` (front-buffered rendering via `androidx.graphics`). Add `androidx.input.motionprediction` to predict ≈1 frame ahead. Target: ≤ 1 frame (8.3 ms at 120 Hz) pen-to-pixel. |
| Pressure | `getPressure()` returns 0..1. The S Pen has 4,096 levels. Normalize per device class (SPEN-06), because some stylus/digitizer pairs never reach 1.0 or send noisy values on first contact. |
| Tilt / orientation | `AXIS_TILT` (0 = perpendicular, up to π/2) and `AXIS_ORIENTATION`. **[verify]** which Galaxy Tab / S Pen generations report tilt; devices without it report 0, so brushes must degrade gracefully. |
| Cancel | Respect `ACTION_CANCEL` and `FLAG_CANCELED` (Android 13+ marks accidental palm touches). Remove the in-progress stroke and don't commit it. |

## 2. Palm rejection
1. **Auto stylus-only mode:** the first time we see `TOOL_TYPE_STYLUS`, switch to stylus-only and show a one-time hint ("Fingers now scroll. Change in Settings"). This is what Samsung Notes users expect.
2. In stylus-only mode, finger touches never draw. One finger pans, two fingers zoom, two-finger tap undoes, three-finger tap redoes.
3. While the pen is hovering or down, ignore new finger pointers that start near the pen's position. Palms land there.
4. If a finger stroke started within ~100 ms before a stylus down, cancel the finger stroke (late palm detection).

## 3. Hover
- The S Pen reports `ACTION_HOVER_ENTER/MOVE/EXIT` up to ~1–2 cm above the screen.
- Show our own cursor: a circle the size of the current brush, or the eraser outline in erase mode. Hide the system hover dot with a null/custom pointer icon (`View.onResolvePointerIcon` or Compose `Modifier.pointerHoverIcon`). **[verify]** Samsung's Air View dot is fully replaceable on One UI 6/7/8.
- Hover over toolbar items shows tooltips (Air View-style).
- Hover never triggers drawing, and hover-exit must clear the cursor even if `EXIT` is missed (timeout fallback).

## 4. Side button
- The modern API reports the button as `MotionEvent.BUTTON_STYLUS_PRIMARY` in `getButtonState()`.
- **[verify]** Some older Samsung firmware delivers button-held touches as private action codes **211 (down), 212 (up), 213 (move)** instead of the standard actions. Map them to the standard actions plus "button pressed" in `:engine:stylus`, so nothing else has to know about it.
- Default behavior: **hold the button = temporary eraser**. Release returns to the previous tool. Configurable: lasso, highlighter, or nothing.
- Pressing the button without touching the screen (hover + click) is reserved for future "quick tool menu" behavior. **[verify]** whether this reaches apps or is intercepted by Air Command.

## 5. Samsung DeX, keyboard covers and mouse
- DeX runs the app in a freeform resizable window. Support live resize without losing the canvas state (`configChanges` or proper state restore).
- Mouse: left-drag pans (or draws when the "draw with mouse" setting is on), wheel scrolls, Ctrl+wheel zooms, right-click opens the context menu.
- Book Cover Keyboard shortcuts (SPEN-10): `Ctrl+Z` / `Ctrl+Shift+Z`, `P` pen, `E` eraser, `H` highlighter, `L` lasso, `Ctrl+F` search, `Ctrl+N` new page, `Ctrl+±` zoom. Holding Meta shows the system shortcut helper (implement `onProvideKeyboardShortcuts`).

## 6. Multi-window and multi-instance
- Declare `android:resizeableActivity="true"`. Support split-screen, pop-up view and Samsung's multi-window.
- **Two notebooks side by side:** allow multiple editor instances (`FLAG_ACTIVITY_MULTIPLE_TASK` and `documentLaunchMode`), so a student can keep the PDF on the left and notes on the right.
- Drag and drop: lasso-selected ink or images drag into other apps as PNG; images and PDFs drop in from other apps.

## 7. Air actions (S Pen Remote SDK)
- Requires Samsung's proprietary S Pen Remote SDK. It must live in a separate `samsung` product flavor so the `foss` build stays fully open. (ADR to be written before SPEN-11.)
- Planned mappings in presentation mode: click = next page, double-click = previous page, gestures = undo/redo.

## 8. Large screens and foldables
- Window size classes drive layout: compact (phone), medium (Fold inner screen, small tabs), expanded (Tab S 11"+).
- Tab S Ultra (14.6"): toolbar can dock on the side to save vertical space. Default to two-page view in landscape.
- Fold: in Flex mode (half-open), the canvas goes on the top half and tools on the bottom (SPEN-12).
- Keep the canvas at the display's peak refresh rate while writing (`Surface.setFrameRate` / `preferredDisplayModeId`), and let it drop when idle to save battery.

## Device matrix
Minimum lab for every milestone exit. Owners record pass/fail in `docs/platform/reports/<milestone>.md`.

| Device | Why | Priority |
|---|---|---|
| Galaxy Tab S10 Ultra / S10+ | Flagship, 14.6"/12.4", 120 Hz, current One UI | P0 |
| Galaxy Tab S9 FE | Mid-range, slower GPU: our perf floor for tablets | P0 |
| Galaxy Tab S7 / S8 | Older but very common in student hands | P1 |
| Galaxy S2x Ultra (built-in S Pen) | Phone layout + S Pen | P1 |
| Galaxy Z Fold (S Pen Fold Edition) | Foldable + Flex mode | P2 |
| Non-Samsung USI/MPP tablet (e.g. Lenovo, Xiaomi Pad + pen) | Make sure we don't hard-code Samsung behavior | P1 |
| Galaxy Tab S6 Lite / A-series with S Pen | Low-end + 60 Hz | P1 |

## Test script (per milestone)
1. Write a full A4 page fast: no gaps, no lag, no stray palm marks.
2. Lightest possible touch draws a hairline; heavy pressure hits max width.
3. Tilt the pencil: shading widens (on tilt-capable devices).
4. Hold the side button and erase, then release: back to the same pen.
5. Hover: cursor matches brush size; leaves no ghost when the pen is lifted away fast.
6. Split screen with a PDF viewer: drag ink out and an image in.
7. DeX with mouse and keyboard: all shortcuts work.
8. Rotate the device mid-stroke: the stroke is committed or cancelled cleanly, never corrupted.
