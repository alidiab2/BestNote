# ADR-0006: Handwriting recognition engine

- **Status:** Proposed
- **Date:** 2026-10-15
- **Owner:** WS-10

## Context
Handwriting search must work on device, in Arabic and English. The F-Droid (`foss`) build can't depend on Google Play Services.

## Options
1. **ML Kit Digital Ink Recognition**: on-device, supports many languages including Arabic, good accuracy. Proprietary, downloads models via Play Services → `play` flavor only.
2. **Open model** (e.g. an ONNX/TFLite online-handwriting model): fully open and works in `foss`. Arabic quality unknown, and training/finetuning may be needed.
3. **Both**: `HandwritingRecognizer` interface; ML Kit in `play`, open model in `foss`.

## Proposal
Option 3. REC-01 builds an Arabic + English test corpus and measures both before acceptance.

## Consequences
Two engines to maintain. The search index format must stay engine-independent (store recognized text + confidence + stroke ids).
