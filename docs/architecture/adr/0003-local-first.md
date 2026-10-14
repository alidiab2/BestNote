# ADR-0003: Local-first, no accounts, no network in the core app

- **Status:** Accepted
- **Date:** 2026-10-15
- **Owner:** Maintainer

## Context
Users' notes are private. Running servers costs money we don't have as a free project, and accounts add friction.

## Decision
- No accounts, no analytics, no ads. The `foss` build declares **no INTERNET permission**.
- Any future sync works through storage the user controls (SAF folder synced by their own cloud app, WebDAV/Nextcloud), in a separate opt-in module.

## Consequences
- Simple privacy story and zero running cost.
- Cross-device sync is harder and comes later (DOC-09).
