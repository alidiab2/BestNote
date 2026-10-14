# ADR-0004: Room for metadata, file packages for content

- **Status:** Accepted
- **Date:** 2026-10-15
- **Owner:** WS-04

## Context
Stroke data is large and binary, and it's read page by page. Library queries (sort, filter, search titles) need indexes.

## Decision
- Notebook **content** lives in a per-notebook package directory using the `.bnote` layout ([file-format.md](../file-format.md)).
- **Room** holds a rebuildable index: notebooks, folders, tags, thumbnails, recognition results.
- If the database is lost, it can be rebuilt by scanning the packages.

## Consequences
- Content files are portable and easy to back up. The DB stays small and fast.
- Two sources of truth must be kept consistent. The package wins; the index is a cache.
