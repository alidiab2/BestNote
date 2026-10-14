# ADR-0001: Native Kotlin + Jetpack Compose, with a View-based ink surface

- **Status:** Accepted
- **Date:** 2026-10-15
- **Owner:** Maintainer

## Context
Ink latency is the product. Cross-platform frameworks (Flutter, React Native, KMP + Compose Multiplatform) add a layer between `MotionEvent` and the GPU and can't use Android's front-buffered rendering APIs directly.

## Decision
- Android-only, Kotlin, Jetpack Compose for all UI.
- The drawing surface is an Android `View` (`InProgressStrokesView` + our page view) hosted in Compose via `AndroidView`, because front-buffered low-latency rendering is View/Surface based.
- Pure logic (model, storage, recognition interfaces) stays in JVM modules, so a future desktop port isn't blocked.

## Consequences
- Best possible latency and full access to stylus APIs.
- No iOS/desktop version without a rewrite of the UI layer (a non-goal for v1).
