# WS-11 Quality, Performance & Release (`QA`)

**Goal:** prove the "fastest, smoothest" claim with numbers, and ship to Play and F-Droid.
**Owns:** `.github/`, benchmark module(s), release config, translation setup

| ID | Task | M | Size | Depends on | Done when |
|---|---|---|---|---|---|
| [ ] QA-01 | CI: build + unit tests + Android lint on every PR | M0 | S | — | Required check on `main` |
| [ ] QA-02 | Static analysis: ktlint/Spotless + Detekt, module-dependency rules check | M1 | M | QA-01 | `feature` → `feature` dependency fails the build |
| [ ] QA-03 | `:benchmark` Macrobenchmark module + Baseline Profiles | M1 | M | — | Startup, library scroll, page scroll, write session benchmarks in CI |
| [ ] QA-04 | Ink latency harness: in-app frame timing overlay + slow-motion camera protocol | M1 | M | — | Protocol doc + first report on Tab S9 |
| [ ] QA-05 | Local-only, opt-in crash reports (user shares the file; no network) | M2 | S | — | — |
| [ ] QA-06 | Play release pipeline: signing, AAB, internal track | M3 | M | — | — |
| [ ] QA-07 | F-Droid build: `foss` flavor with no Play Services, reproducible build | M3 | M | ADR-0006 | Metadata merged in fdroiddata |
| [ ] QA-08 | Translations via Weblate (Arabic, English, + community) | M3 | S | DS-09 | — |
| [ ] QA-09 | Device lab matrix + manual test script per milestone | M1 | S | — | See [device matrix](../platform/samsung-s-pen.md#device-matrix) |
| [ ] QA-10 | Screenshot tests for design-system components (Roborazzi or Paparazzi) | M2 | M | DS-01 | — |
