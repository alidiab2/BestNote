# Approved direction: A · Atelier

Stationery made digital: warm paper chrome, ink is the hero. Approved by the owner; implemented in
`:core:designsystem` (DS-01, DS-02). Code is the source of truth: `theme/Color.kt`, `Type.kt`,
`Shape.kt`, `Motion.kt`, `Theme.kt`. Live sample: `showcase/ThemeShowcase.kt` (previews), and in debug
builds `adb shell am start -n app.bestnote.debug/app.bestnote.core.designsystem.showcase.ThemeShowcaseActivity [--ez dark true] [--ez rtl true]`.

## Color
| Role | Light | Dark (graphite) | Token |
|---|---|---|---|
| Paper / background | #F6F4EF | #121417 | `colorScheme.background` |
| Sidebar / surface-container | #EFECE5 | #1C1F24 | `colorScheme.surfaceContainer`, `BestNoteTheme.colors.sidebar` |
| Surface (cards, pills) | #FFFFFF | #1C1F24 | `colorScheme.surface` |
| Desk (editor bg, radial) | #F4F1EB → #E1DCD2 | #1E2127 → #0E1013 | `BestNoteTheme.colors.deskBrush()` |
| Outline / variant | #E2DED5 / #ECE7DD | #353A44 / #2A2E36 | `outline`, `outlineVariant` |
| Ink (onSurface) / variant | #1E2433 / #3B4150 | #E9E7E1 / #C3C6CF | `onSurface`, `onSurfaceVariant` |
| Muted text | #6A6F7C | #8E939E | `colors.muted` |
| Primary (fountain blue) / container | #2A4BD7 / #E3E8FB | #A9B6FF / #263572 | `primary`, `primaryContainer` |
| Strong button / on | #1E2433 / #F6F4EF | #E9E7E1 / #121417 | `colors.strongButton`, `onStrongButton` |
| Error (vermilion) | #D9462B | #FF8A70 | `error` |
| Success (sage) | #2F7D57 | #7CC39C | `colors.success` |
| Warning / quick note | #FFF5D9 on #6E5A22 | #3A3320 on #F3DFA2 | `colors.warning`, `onWarning` |
| AI accent | #A9B6FF | #A9B6FF | `colors.aiAccent` |

- Ink defaults (`InkPalette`): #1E2433, #2A4BD7, #D9462B, #2F7D57, highlighter #F7D84A.
- Covers (`CoverPalette`): navy #23314F, terracotta #B4553A, sage #5F8069, mustard #C99A2E, lilac #7D6CA6, black #1F1F22.
- Paper (`colors.paper`, never themed): page #FFFEFB, ruled line #DCE5F1, margin rgba(226,120,105,.45). Paper stays light in dark mode; "dark paper" is a page template.
- Dynamic color: opt-in (`dynamicColor = false` by default, also the `UserPreferences` default). It recolors chrome only.

## Type
Fraunces (display, titles), Instrument Sans (UI), Caveat (handwriting samples only), IBM Plex Sans Arabic
(swapped in for display + UI when the locale is Arabic). Bundled in `res/font`, subset, SIL OFL
(licenses in `core/designsystem/src/main/assets/licenses`); works offline and without Play services.
Fraunces uses its `opsz` axis: 40 for display, 22 for titles/headlines.

| Role | Size / line | Slot |
|---|---|---|
| Display | 40 / 44 (1.1) | `displayMedium` |
| Title large | 24 | `titleLarge` (25 = `headlineMedium`) |
| Title | 19 | `titleMedium` |
| Body | 15 | `bodyMedium` |
| Label | 13–14 | `labelMedium`, `labelLarge` |
| Caption | 12.5 | `BestNoteTheme.type.caption` |
| Overline | 12, 0.12em tracking | `BestNoteTheme.type.overline` |

## Shape and elevation
Pills full (`BestNoteShapes.Pill`), cards 18 dp, small controls 12 dp, tool tray 24 dp, sheets 28 dp,
notebook cover 3/10/10/3 dp with the small radius on the spine (start) side. Start/end corners, so chrome
mirrors in RTL; the page never does. Soft long warm shadows (`Modifier.softShadow(Elevation.High, …)` =
0 16 dp 34 dp at 45% warm brown) and 1 dp hairlines (`Modifier.hairline`) instead of heavy borders.

## Motion
Springs: standard 0.85 / 380 (~350 ms), snappy 0.7 / 600 (~280 ms, tool switch), gentle 0.9 / 200 (sheets).
Durations: 120 / 250 / 350 / 400 ms. Patterns: `Motion.Enter|Exit.sheet/popover/content/toolbar`,
`Motion.SharedElement.bounds()` (notebook → page), `Motion.TrayLift` (active pen lifts −8 dp, ×1.06),
`Motion.Toolbar.WritingAlpha` 0.4 while writing. Reduced motion (`BestNoteTheme.reducedMotion`, from the
system "Remove animations") swaps transforms for fades. No raw motion values outside `Motion.kt`
(`MotionTokenGuardTest`).
