# WS-08 Design System & Motion (`DS`)

**Goal:** the most beautiful notes app on Android, with motion that feels physical and never slows the user down.
**Owns:** `:core:designsystem`, `:core:ui`, editor chrome (toolbars, pickers, sheets)
**Spec:** [`../design/design-principles.md`](../design/design-principles.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [x] DS-01 | Theme v1: color roles, typography scale, shapes; light/dark; dynamic color opt-in | M1 | M | — | Figma (or Penpot) file + Compose tokens match 1:1 |
| [x] DS-02 | Motion tokens: springs, durations, easing, enter/exit patterns | M1 | S | — | No hard-coded durations anywhere outside `Motion` (lint check) |
| [x] DS-03 | Editor toolbar: floating, collapsible, dockable top/left/right, left-handed mode | M1 | L | DS-02 | Collapses to a pill while writing; one tap to restore |
| [x] DS-04 | Pen picker and color palette: presets, custom colors, size preview with live stroke | M1 | M | INK-06 | Second tap on the active tool opens the picker as a popover anchored to it |
| [x] DS-05 | Adaptive layouts: phone, tablet, foldable, DeX window, using WindowSizeClass | M2 | M | — | Every screen reviewed at compact/medium/expanded |
| [ ] DS-06 | Haptics map (tool switch, snap, undo) with system-setting respect | M2 | S | — | — |
| [ ] DS-07 | Icon set: custom tool icons (pen types, eraser modes, lasso) | M2 | M | — | — |
| [ ] DS-08 | Accessibility: TalkBack labels, 48 dp targets, contrast AA, reduced motion | M3 | M | — | Accessibility Scanner shows zero issues |
| [ ] DS-09 | Localization: Arabic (RTL) + English at launch; string extraction rules | M2 | M | — | Full RTL pass. The canvas never mirrors; chrome does |
| [ ] DS-10 | App icon, splash, Play listing visuals | M3 | M | — | — |
| [x] DS-11 | Dark theme contrast pass: warm graphite ladder, AA contrast for text/icons/borders, swatch rings, dark pen art, theme-root content color | M2 | M | DS-01 | `DarkContrastTest` passes; editor chrome and library readable in dark on a tablet |
| [x] DS-12 | Anchored tool popovers: the pen, colour, width and eraser popovers (and the Paper sheet) open at what was tapped with a caret pointing at it — below a top tray, above a bottom one, beside a side one — flip when short and stay on screen | M2 | S | DS-04 | `PopoverMathTest` passes; pen popover screenshot shows the caret on the tapped pen |
| [x] ONB-01 | First-run onboarding (`:feature:onboarding`): welcome, live pen/finger check, real palm-rejection scratch canvas, start; skippable, shown once, re-openable from Settings → About → "Show welcome again" | M2 | M | SPEN-02 | en + ar; "I don't have a pen" sets the existing finger-drawing preference; nothing animates under an active pen |
