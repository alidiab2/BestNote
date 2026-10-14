# ADR-0007: Optional cloud AI for Smart Board (amends ADR-0003)

- **Status:** Proposed
- **Date:** 2026-10-15
- **Owner:** WS-12

## Context
Smart Board (whiteboard photo → structured notes) needs a strong vision-language model. No on-device model today reads a messy board well enough. ADR-0003 says the core app has no network access and no accounts.

## Decision
- The AI pipeline is split into **provider-neutral, pure-JVM** modules (`:ai:vision-api`, `:ai:math`, `:engine:layout`) and **provider modules** (`:ai:vision-gemini`, …). Only provider modules declare `INTERNET`.
- **Bring your own key.** The user pastes their own API key, stored with the Android Keystore. No BestNote server, no account, no proxy.
- **Opt-in and off by default.** The core app works fully without it. The photo goes directly from the device to the provider the user chose, and the UI says so before the first upload.
- Build flavors: `foss` ships **without** provider modules (no network permission). `play` and `full` (GitHub/F-Droid "NonFreeNet" variant) include them.

## Consequences
- ADR-0003's promise ("the core app never touches the network") holds for `foss`, and AI is a clearly separated add-on.
- Prompt quality is protected by tests plus an evaluation set (AI-09), and deterministic post-processing (`:engine:layout`) keeps output clean regardless of provider.
- A future on-device model (AI-10) can implement `VisionProvider` without touching the pipeline.
