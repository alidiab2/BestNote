# WS-08 Design System & Motion (`DS`)

**Goal:** the most beautiful notes app on Android, with motion that feels physical and never slows the user down.
**Owns:** `:core:designsystem`, `:core:ui`, editor chrome (toolbars, pickers, sheets)
**Spec:** [`../design/design-principles.md`](../design/design-principles.md)

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [ ] DS-01 | Theme v1: color roles, typography scale, shapes; light/dark; dynamic color opt-in | M1 | M | — | Figma (or Penpot) file + Compose tokens match 1:1 |
| [ ] DS-02 | Motion tokens: springs, durations, easing, enter/exit patterns | M1 | S | — | No hard-coded durations anywhere outside `Motion` (lint check) |
| [ ] DS-03 | Editor toolbar: floating, collapsible, dockable top/left/right, left-handed mode | M1 | L | DS-02 | Collapses to a pill while writing; one tap to restore |
| [ ] DS-04 | Pen picker and color palette: presets, custom colors, size preview with live stroke | M1 | M | INK-06 | Second tap on the active tool opens the picker as a popover anchored to it |
| [ ] DS-05 | Adaptive layouts: phone, tablet, foldable, DeX window, using WindowSizeClass | M2 | M | — | Every screen reviewed at compact/medium/expanded |
| [ ] DS-06 | Haptics map (tool switch, snap, undo) with system-setting respect | M2 | S | — | — |
| [ ] DS-07 | Icon set: custom tool icons (pen types, eraser modes, lasso) | M2 | M | — | — |
| [ ] DS-08 | Accessibility: TalkBack labels, 48 dp targets, contrast AA, reduced motion | M3 | M | — | Accessibility Scanner shows zero issues |
| [ ] DS-09 | Localization: Arabic (RTL) + English at launch; string extraction rules | M2 | M | — | Full RTL pass. The canvas never mirrors; chrome does |
| [ ] DS-10 | App icon, splash, Play listing visuals | M3 | M | — | — |
