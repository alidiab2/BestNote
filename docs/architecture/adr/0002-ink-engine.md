# ADR-0002: Build on `androidx.ink` instead of a custom stroke engine

- **Status:** Accepted
- **Date:** 2026-10-15
- **Owner:** WS-01

## Context
Writing a stroke engine (mesh generation, pressure modeling, front-buffer rendering, prediction, serialization) from scratch would take months. Google's `androidx.ink` (stable 1.0) provides brushes, stroke geometry, `InProgressStrokesView` with front-buffered rendering, hit-testing geometry and a compact storage encoding. It's open source (Apache-2.0).

## Decision
Use `androidx.ink` for wet ink, stroke geometry, rendering and encoding. Our value goes into brush tuning, S Pen behavior, the page/tile system and UX.

## Consequences
- Fast start, Google-maintained low-latency path.
- Brush expressiveness is limited to what `androidx.ink` brush families support. Custom textures (pencil) may need its texture API or a custom renderer later.
- We wrap ink types behind our own `:core:model` types at module boundaries, so a future engine swap stays contained.
