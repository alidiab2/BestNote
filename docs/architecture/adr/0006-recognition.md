# ADR-0006: Handwriting recognition engine

- **Status:** Accepted (ML Kit Digital Ink in the app; open-model engine for `foss` still to do)
- **Date:** 2026-10-15
- **Owner:** WS-10

## Context
Handwriting search must work on device, in Arabic and English. The F-Droid (`foss`) build can't depend on Google Play Services.

## Options
1. **ML Kit Digital Ink Recognition**: on-device, supports many languages including Arabic, good accuracy. Proprietary, downloads models via Play Services → `play` flavor only.
2. **Open model** (e.g. an ONNX/TFLite online-handwriting model): fully open and works in `foss`. Arabic quality unknown, and training/finetuning may be needed.
3. **Both**: `HandwritingRecognizer` interface; ML Kit in `play`, open model in `foss`.

## Decision
Option 3 as the architecture, with ML Kit as the first engine. REC-01 measured ML Kit on the corpus (see
[handwriting-recognition-eval.md](../../research/handwriting-recognition-eval.md)): English is near exact, Arabic readable but weak on the
synthetic corpus. The open-model engine for `foss` (QA-07) is not built or measured yet; `HandwritingRecognizer` is where it plugs in.

- Models: `ar` and `en-US`, downloaded on request (about 50 MB), then offline.
- ML Kit gives no confidence scores, so the language is chosen from the writing direction of the ink (`ReadingChooser`).
- The index stores per word: shown text, all top candidates (folded), and the word's box in page points, so a better engine only needs a re-index.

## Consequences
Two engines to maintain. The search index format must stay engine-independent (store recognized text + confidence + stroke ids).
