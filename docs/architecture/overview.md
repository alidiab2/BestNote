# Architecture Overview

## Stack
Kotlin · Jetpack Compose (Material 3) for UI · Android Views for the ink surface (front-buffered rendering needs a `SurfaceView`-backed view) · `androidx.ink` · Hilt · Room + binary page files · DataStore · Coroutines/Flow · type-safe Navigation.
minSdk 29 · target/compile SDK 37 · JDK 17.

## Layers and modules

```
:app                      Hilt entry, navigation, windowing (multi-instance, DeX)
  │
:feature:*                Screens + ViewModels (library, editor, settings)
  │
:engine:*                 The hard parts, UI-framework agnostic where possible
  ├─ canvas               page surface, viewport, tiles, scene graph, undo, selection
  ├─ ink                  brushes and stroke geometry (androidx.ink)
  ├─ stylus               MotionEvent → StylusEvent, palm rejection, S Pen quirks
  ├─ pdf                  PDF render/import/export
  ├─ recognition (JVM)    handwriting/shape recognition + search index interfaces
  └─ layout (JVM)         Smart Board layout: lecture flow, template regions, ruler geometry, color semantics
  │
:ai:*                     Smart Board AI (opt-in)
  ├─ vision-api (JVM)     expert-student prompt, wire schema, mapper, pipelines
  ├─ math (JVM)           LaTeX sanitizer
  └─ vision-<provider>    network providers, not in the foss build (ADR-0007)
  │
:core:*
  ├─ model (JVM)          domain types shared by everyone
  ├─ common (JVM)         dispatchers, result types, small utils
  ├─ storage (JVM)        .bnote container I/O
  ├─ database             Room metadata index
  ├─ datastore            user preferences
  ├─ designsystem         theme, Motion tokens, spacing
  └─ ui                   shared composables
```

### Dependency rules (enforced by QA-02)
- `feature` → `engine`, `core`. **Never** `feature` → `feature` (navigate through routes in `:app`).
- `engine` → `core:model`, `core:common`. `engine:canvas` may use `engine:ink`, `engine:stylus` and `engine:pdf`.
- `core` never depends on `engine` or `feature`.
- `ai` → `engine:layout`, `core`. Features may use `ai:vision-api`; only provider modules may use the network.
- `core:model`, `core:common`, `core:storage`, `engine:recognition`, `engine:layout`, `ai:vision-api` and `ai:math` are **pure JVM**, so they're unit-testable without Android.

## The life of a stroke
1. **Input (`engine:stylus`)**: `MotionEvent` (+ historical samples) becomes `StylusEvent`. Palm rejection decides: draw, navigate, or drop. Side button → temporary tool.
2. **Wet ink (`engine:canvas` + `engine:ink`)**: `InProgressStrokesView` renders on the front buffer with motion prediction. The UI thread does no allocation per sample.
3. **Commit**: on `ACTION_UP` the finished `Stroke` is added to the page's scene graph and spatial index, and an undo command is pushed.
4. **Dry ink**: the stroke is drawn into the tile cache for the current zoom bucket. The wet stroke is removed only after the dry frame is presented (no flicker).
5. **Persist (`core:storage`)**: the page is marked dirty. Autosave encodes the changed page chunk off the main thread and writes atomically.
6. **Index (`engine:recognition`)**: later, when idle, the page is queued for recognition and search indexing.

## Threading
| Work | Thread |
|---|---|
| Input, wet ink | UI thread + front-buffer render thread (owned by androidx.ink/graphics) |
| Dry tile rasterization | dedicated render dispatcher (single thread per document) |
| PDF rendering | `pdf` dispatcher (1–2 threads, PdfRenderer isn't thread-safe per document) |
| Encode/save | `Dispatchers.IO` with per-notebook mutex |
| Recognition/indexing | WorkManager / low-priority dispatcher |

## State management
- ViewModels expose immutable `UiState` via `StateFlow`. User actions come in as a sealed `Action`/`Intent` type (unidirectional data flow).
- The canvas document model is **not** Compose state. It's a mutable scene graph owned by `engine:canvas`, with change notifications. This keeps 10k-stroke pages out of recomposition.

## Performance budgets
| Scenario | Budget |
|---|---|
| Pen-to-pixel latency (120 Hz) | ≤ 1 frame |
| Frame time while writing / panning | < 8 ms (no jank) |
| Cold start → library | < 600 ms (Baseline Profiles) |
| Open 50-page notebook | < 300 ms to first page |
| Open 500-page PDF | < 1 s to first page |
| Memory, 300-page notebook scrolling | flat (page recycling) |

## Build flavors (planned)
| Flavor | Contents | Distribution |
|---|---|---|
| `foss` | 100% open-source dependencies, no Play Services, no network permission | F-Droid, GitHub |
| `play` | May add ML Kit recognition / document scanner | Google Play |
| `samsung` (extends `play`) | S Pen Remote SDK for Air actions | Galaxy Store / Play |

See also: [file format](file-format.md) · [ADRs](adr/) · [S Pen spec](../platform/samsung-s-pen.md)
