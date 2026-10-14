# ADR-0005: PDF engine

- **Status:** Proposed
- **Date:** 2026-10-15
- **Owner:** WS-05

## Context
We need fast rendering of large PDFs, a text layer (search, text-snapping highlighter), links/outline and export with annotations.

## Options
1. **Android `PdfRenderer`**: built in, zero APK cost. Limited: no text extraction below API 35, no outline, single-threaded per document. Newer versions and `androidx.pdf` add more, but not on all our minSdk devices.
2. **PDFium** (BSD-3, used by Chrome): fast, text layer, links, outline. Adds ~4–6 MB native per ABI. Needs JNI bindings (existing open-source wrappers).
3. **MuPDF**: excellent quality and features, but **AGPL**, which would force the whole app to AGPL.

## Proposal
Start M2 rendering on `PdfRenderer` behind the `PdfDocumentSource` interface (already in `:engine:pdf`). Spike PDFium in parallel (PDF-02). Switch if it meets the 500-page < 1 s budget. Reject MuPDF unless D1 (license) goes AGPL-compatible.

## Consequences
The interface keeps the engine swappable. Export (PDF-05) may need a separate writer library either way. Evaluate PdfBox-Android (Apache-2.0).
