# Design Principles: UI, Motion and Feel

Owner: WS-08. Every UI PR is reviewed against this page.

## 1. The page is the hero
- Chrome (toolbars, sheets) takes ≤ 8% of the screen in the editor by default.
- While the pen is down, the toolbar fades to 40% opacity. If the user writes near it, it collapses to a pill.
- No snackbars or banners over the writing area. Feedback appears at the screen edge.

## 2. Tools are one gesture away
- Tap a tool to select it. Tap it again to open its settings popover, anchored to the tool. Never a full-screen dialog.
- Favorite pens sit in the toolbar as swatches showing a live preview of the stroke.
- The S Pen side button gives a temporary tool without touching the UI.

## 3. Motion
All motion uses tokens from `:core:designsystem` → `Motion`. **No raw durations or easing curves in feature code.**

| Token | Use | Spec |
|---|---|---|
| `Motion.Spring.Standard` | Container moves, cards, notebook open/close | spring, dampingRatio 0.85, stiffness 380 (~350 ms) |
| `Motion.Spring.Snappy` | Tool selection, toggles, small popovers, tray lift | spring, dampingRatio 0.7, stiffness 600 (~280 ms) |
| `Motion.Spring.Gentle` | Sheets, panels, page manager | spring, dampingRatio 0.9, stiffness 200 |
| `Motion.Duration.Short` | Fades, color changes | 120 ms |
| `Motion.Duration.Medium` | Container transforms | 250 ms |
| `Motion.Duration.Long` | Library → editor "open notebook" | 400 ms |

Enter/exit patterns live in `Motion.Enter` / `Motion.Exit` and degrade to fades under reduced motion. `MotionTokenGuardTest` (`:core:designsystem:test`) fails the build on raw durations, easing or spring parameters outside `Motion.kt`. Approved token values: [`approved-direction.md`](approved-direction.md).

Rules:
- **Motion explains space.** Opening a notebook zooms from its cover (shared element). The page manager zooms *out* from the current page. Closing reverses it.
- **Interruptible.** Every animation can be grabbed or reversed mid-flight (springs, not fixed tweens, for anything the user drives).
- **Never in the pen's way.** Nothing animates under the pen while writing. Ink has no "appear" animation, ever: zero latency beats polish.
- **Physics for direct manipulation.** Page reorder, lasso move and fling use velocity-aware springs.
- **Reduced motion:** respect the system "Remove animations" setting. Swap transforms for fades.

## 4. Haptics
Use sparingly, only for confirmations the eye might miss:
- Snap (shape snapped, rotation hit 0/45/90°): `CLOCK_TICK`
- Tool switch via S Pen button: `KEYBOARD_TAP`
- Undo/redo gesture recognized: `CONFIRM` (API 30+)
- Never on every stroke. Respect the system haptics setting.

## 5. Visual language
- Material 3 base, with a quieter palette: neutral surfaces and one accent. Paper colors are a separate "paper" palette and never follow dynamic color.
- Dark mode: chrome goes dark. Paper stays white by default, with an optional "dark paper" that inverts ink colors smartly (black ↔ white; colors keep their hue).
- Corner radius: 18 dp cards, 12 dp small controls, 24 dp tool tray, 28 dp sheets, full pill for the floating toolbar (see `approved-direction.md`).
- Icons: custom tool icons (pen types differ visibly); Material Symbols Rounded for everything else.
- Thumbnails and covers have a subtle paper texture and a 1 dp shadow. They should read as physical objects.

## 6. Arabic and RTL
- UI chrome mirrors in RTL. **The canvas never mirrors.** Paper, ink and PDFs keep their geometry.
- The toolbar's left-handed mode is a separate setting from RTL.
- Fonts: a typeface with real Arabic support for UI and text boxes (e.g. IBM Plex Sans Arabic or Noto Sans Arabic). Test mixed Arabic/English strings.
- Search normalizes Arabic (alef forms, taa marbuta, yaa/alef maqsura, diacritics).

## 7. Adaptive layout
| Width class | Library | Editor |
|---|---|---|
| Compact (phone) | 2-column grid, bottom bar | Toolbar bottom, single page |
| Medium (Fold, small tab) | 3–4 columns, nav rail | Toolbar top, thumbnails as a sheet |
| Expanded (Tab S) | 5–6 columns, nav rail + folder pane | Toolbar top or side, thumbnail sidebar, two-page spread in landscape |

## 8. Review checklist
- [ ] Uses only theme colors and `Motion` tokens
- [ ] Checked in light, dark, RTL, compact and expanded
- [ ] Touch targets ≥ 48 dp; TalkBack labels present
- [ ] Nothing animates under an active pen
- [ ] Screenshot or video in the PR
