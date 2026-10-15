# Tech stack research (October 2026)

Task: `RES-01`. Scope: confirm or challenge the current stack and the open decisions D2–D5 in [`roadmap.md`](../roadmap.md). Every recommendation is filtered through three constraints: **Apache-2.0 or GPL-3.0 compatible**, **`foss` flavor buildable for F-Droid without Play Services or network**, and **Galaxy Tab plus S Pen first**.

Items marked **[verify]** could not be confirmed from a primary source and need a spike or a device check before we build on them.

## Summary

| Area | Recommendation | Runner-up | Disagrees with an ADR? |
|---|---|---|---|
| 1. Ink and latency | Keep `androidx.ink` 1.0.x + `graphics-core` front buffer + motion prediction | `LowLatencyCanvasView` with our own mesh | No (confirms ADR-0002) |
| 2. PDF (D2) | `androidx.pdf` core APIs on top of the platform PDF engine | Own thin JNI over PDFium built from source | **Partly** (ADR-0005) |
| 3. Recognition (D3) | ML Kit Digital Ink in `play`; offline renderer + open OCR model in `foss`, behind the same interface | Train our own small online-ink model | No (refines ADR-0006) |
| 4. S Pen | Standard `MotionEvent` APIs only; drop the Samsung SDK flavor from the plan | Keep `samsung` flavor for older pens only | **Yes** (D4 / SPEN-11) |
| 5. Smart Board AI | BYOK multi-provider, picked by our own eval set; "handwritten" output = our own ink font + jitter | Diffusion handwriting synthesis (research only) | No (confirms ADR-0007) |
| 6. Other libraries | Room + bundled SQLite with FTS5, command-based undo, tile cache, SAF + `dav4jvm` for sync | Room `@Fts4` on the framework SQLite | No |

## 1. Ink and latency

**Recommendation: keep `androidx.ink` 1.0.x for wet ink and stroke geometry, keep `graphics-core` 1.0.4 and `input-motionprediction` 1.0.0.** Watch the 1.1 line but don't adopt it until it's stable.

- `androidx.ink` 1.0.0 is stable. 1.1.0 is in alpha (alpha06 on 2026-07-29). The 1.1 alphas fix float-precision artifacts at stroke ends on some devices after OS upgrades, add a ByteArray-based KMP storage API, add `BrushBehavior.developerComment` for brush-designer UIs, and ship an `InProgressStrokes` composable. The end-of-stroke fix is worth cherry-picking as soon as 1.1 goes beta. ([release notes](https://developer.android.com/jetpack/androidx/releases/ink))
- `graphics-core` 1.0.2–1.0.4 fixed front-buffer flicker on some Android 14+ devices and on API < 33. That's another reason not to drive `GLFrontBufferedRenderer` by hand: `InProgressStrokesView` already wraps those device quirks. ([LowLatencyCanvasView](https://developer.android.com/reference/androidx/graphics/lowlatency/LowLatencyCanvasView), [low-latency blog](https://medium.com/androiddevelopers/stylus-low-latency-d4a140a9c982))
- Google's own `androidx.pdf` annotation editor is built on `androidx.ink`, which is a good signal that the library will stay maintained. ([pdf release notes](https://developer.android.com/jetpack/androidx/releases/pdf))

**Lessons from other apps**
- **Saber** (GPL-3.0, Flutter): cross-platform, but Flutter can't use Android's front-buffer or motion-prediction libraries directly and waits for the next vsync. That costs at least one frame on Android. Confirms native Kotlin (ADR-0001). ([Saber](https://github.com/saber-notes/saber), [Flutter #110431](https://github.com/flutter/flutter/issues/110431))
- **Goodnotes for Android/Windows** is the iPad Swift engine compiled to WebAssembly and shipped as a PWA. Its target is "more than 60 fps", and users report writing lag on low-RAM tablets. A native front-buffered app can clearly beat it on Galaxy Tabs. ([swift.org](https://swift.org/blog/bringing-goodnotes-to-web-with-swift/), [web.dev](https://web.dev/case-studies/goodnotes))
- **Samsung Notes**: Samsung/Wacom quote 2.8 ms S Pen latency on Tab S8/S9 in Samsung Notes. That's the digitizer response time, not pen-to-pixel latency, so don't treat it as our target. ([Wacom](https://wacom.com/en-cl/about-wacom/news-and-events/2022/1469))
- **Rnote / Xournal++** (GPL-3.0, desktop): vector strokes with cached per-page rendering. Xournal++ 1.3.5 had to cut off-screen PDF cache retention. Memory caps on the tile cache matter from day one (see §6).
- **Notable** (GPL-3.0, Kotlin, Boox e-ink): useful only as a Kotlin reference. E-ink latency tricks don't apply to us.

**Latency targets (no change to the S Pen spec):** ≤ 1 frame wet ink at 120 Hz (8.3 ms app-side), plus prediction ≈ 1 frame ahead. Measure end to end with a 240 fps camera in the QA-09 harness, because no published millisecond figures exist for `InProgressStrokesView`. Smoothing: rely on the brush's built-in input modeling. Don't add our own smoothing filter on the wet path. Run heavier smoothing (shape snapping, beautify) only on committed strokes.

**Runner-up:** `LowLatencyCanvasView` with our own mesh/tessellation. More control (textured pencil), but months of work. Revisit only if pencil texture is impossible with the ink texture API.
**Risk:** brush expressiveness (already noted in ADR-0002). **F-Droid:** fine (Apache-2.0, Google Maven).

## 2. PDF engine (D2)

| Option | License | APK cost | Text / search / select | Links / outline | Write annotations | F-Droid |
|---|---|---|---|---|---|---|
| `androidx.pdf` 1.0.0-beta01 (Aug 2026) | Apache-2.0 | small (uses the platform engine) | Yes | Links yes; outline **[verify]** | Yes, `EditablePdfDocument` / `PdfWriteHandle`, `@ExperimentalPdfApi` | Yes |
| Framework `PdfRenderer` / `PdfRendererPreV` directly | platform | 0 | Yes on newer devices (S Pen tabs are Android 13+) | Link and goto-link content classes exist | `PdfAnnotation`, `StampAnnotation`, `HighlightAnnotation`, `FreeTextAnnotation` classes **[verify API level]** | Yes |
| PDFium via JNI (`pdfium-binaries` or PdfiumAndroid forks) | BSD-3 / Apache-2.0 | ~3–5 MB per ABI | Yes (FPDFText) | Yes | Yes (FPDFAnnot + save), but the Android wrappers don't expose it | **Prebuilt `.so` is rejected**: F-Droid must build PDFium from source (heavy GN toolchain) |
| MuPDF | AGPL-3.0 or commercial | ~8–15 MB | Yes | Yes | Yes | Yes, but see below |
| PdfBox-Android | Apache-2.0 | ~5 MB+ (Java) | Extract only | No | Yes | Yes, but the port looks unmaintained |

**Recommendation: build `:engine:pdf` on `androidx.pdf`'s core APIs (`pdf-core` / `PdfDocument` / `SandboxedPdfLoader`), not on its fragment UI.**
- Since 2024 the framework PDF engine (Google's PDFium build) ships as an updatable mainline module, with `PdfRendererPreV` back to Android R. It adds search, selection, links, forms and annotations, and `androidx.pdf` backports read/render down to minSdk 28. ([Android 15 DP2](https://android-developers.googleblog.com/2024/03/the-second-developer-preview-of-android-15.html), [PdfRendererPreV](https://developer.android.com/reference/android/graphics/pdf/PdfRendererPreV))
- That gives us PDFium-grade rendering and a text layer **without shipping native code**. That's the cleanest F-Droid story and a 0 MB APK cost.
- Rendering runs in a sandboxed service, which isolates crashes from malicious PDFs.
- We keep our own `androidx.ink` canvas over the PDF page and don't use `EditablePdfViewerFragment` UI. We only use the write API to flatten our strokes into the PDF on export (PDF-05).

**Runner-up: own thin JNI over PDFium**, built from source in CI. Use only if the M2 "500 pages < 1 s" budget fails, or the write API stays experimental past M2.

**Risks:** `androidx.pdf` is beta and the editing APIs are experimental (expect breaking renames, as happened in alpha19 and beta01). Feature level depends on the device's SDK-extension level (13 for the full feature set, 19 for image selection), so Android 10 devices (our minSdk 29) get render-only. Plan a raster fallback export for them.

**Disagreement with ADR-0005:** the ADR proposes plain `PdfRenderer` first and a parallel PDFium spike. I recommend `androidx.pdf` as the primary engine and demoting PDFium to a contingency. The reason is F-Droid: the ADR's "~4–6 MB" estimate leaves out that F-Droid won't accept prebuilt PDFium and would need a full Chromium-style source build. Also add to the ADR: MuPDF's AGPL terms, as Artifex reads them, rule out shipping it next to proprietary components, so it would conflict with our `play` flavor (ML Kit) even under GPL-3.0. ([MuPDF Android docs](https://mupdf.readthedocs.io/en/latest/android-library.html))

## 3. Handwriting recognition and search (D3)

**Recommendation: keep ADR-0006 option 3 (two engines behind `HandwritingRecognizer`), with a concrete `foss` engine.**
- **`play` flavor: ML Kit Digital Ink.** It covers 300+ languages including Arabic, runs on device, and uses ~20 MB models downloaded on demand. It's proprietary and the model download needs network, so it's excluded from `foss` anyway. ([ML Kit blog](https://developers.googleblog.com/en/digital-ink-recognition-in-ml-kit), [languages](https://developers.google.com/ml-kit/vision/digital-ink-recognition/base-models))
- **`foss` flavor: render committed ink to a clean grayscale line image, then run an offline text recognizer.** The best open candidate is **PaddleOCR PP-OCRv5 mobile** recognizers (Apache-2.0, Arabic and Latin models ~8 MB each in ONNX), run through ONNX Runtime (MIT). We found **no open stroke-based (online) recognizer with Arabic support**. Rendering ink to an image loses timing data but gives us real, maintained models. ([PP-OCR ONNX](https://huggingface.co/monkt/paddleocr-onnx), [Arabic PP-OCRv3 ONNX](https://huggingface.co/deepghs/paddleocr/blob/main/rec/arabic_PP-OCRv3_rec/model.onnx))
- PP-OCR is trained mainly on printed text, so **handwriting accuracy (especially Arabic) is unproven [verify in REC-01]**. If it falls short, fine-tune the recognizer on rendered online-ink data such as Google's Apache-2.0 InkSight derenderings ([HF](https://huggingface.co/Derendering)) plus our own REC-01 corpus.
- **Tesseract: not suitable.** It's built for printed text, and its handwriting accuracy is poor. Skip it.
- **Search index:** store per-word text, alternates, confidence and stroke ids (as ADR-0006 says), so re-recognizing with a better engine is just a background re-index.

**Math (REC-08):** ink → image → **TexTeller** (Aug 2025, state of the art on handwritten math expression recognition, open model/data/code) or Pix2Text. Both are image models of a few hundred MB, so they're realistic as an optional download, not bundled. Google's MathWriting dataset is CC BY-NC-SA, so don't use it to train anything we ship. ([TexTeller paper](https://arxiv.org/abs/2508.09220)) The license of each model's weights is **[verify]**.

**Runner-up:** train a small online-ink LSTM/Transformer ourselves (TFLite/ONNX). Best accuracy per MB, but it needs Arabic online-ink data we don't have.
**Risks:** `foss` Arabic quality, and ONNX Runtime adds ~6–8 MB per ABI.

## 4. S Pen specifics

**Recommendation: standard Android APIs cover everything we need. Remove the Samsung SDK from the plan.**

- **Air actions / S Pen Remote SDK:** the Galaxy S25 Ultra (Jan 2025) and Tab S11 (Sep 2025) S Pens **dropped Bluetooth**, so Air actions no longer exist on current devices, and the Tab S12 is reported to follow. The SDK is foreground-only, proprietary, and not formally deprecated, but it's dead hardware. ([9to5Google](https://9to5google.com/2025/09/04/samsung-galaxy-tab-s11-s-pen-changes/), [Samsung Air actions docs](https://developer.samsung.com/galaxy-spen-remote/air-actions.html)) **Disagrees with D4** ("separate `samsung` flavor, M4"): recommend **skip**, and replace SPEN-11 presentation control with keyboard, page-turner (HID) and on-screen gestures, which work everywhere.
- **Hover:** `ACTION_HOVER_*` plus a custom pointer icon. The spec in `samsung-s-pen.md` is fine.
- **Side button:** `BUTTON_STYLUS_PRIMARY` in `getButtonState()`. Keep the 211/212/213 fallback as **[verify]** on Tab S7/S8 only.
- **Palm rejection:** follow Google's guide. Treat `ACTION_CANCEL`, and on API 33+ `ACTION_POINTER_UP` with `FLAG_CANCELED`, as "roll back this stroke", and keep a per-stroke history for rollback. On API ≤ 32 no flag exists, so our heuristics (stylus-only mode, ignore touches near the pen) carry the load. ([palm rejection guide](https://developer.android.com/develop/ui/compose/quick-guides/content/reject-stylus-palm-touches), [advanced stylus](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features)) `TOUCH_MAJOR`/`SIZE` is a useful extra palm signal, but its values vary by vendor, so tune it per device class.
- **Tilt / orientation:** `AXIS_TILT`, `AXIS_ORIENTATION`. No change.
- **Handwriting in text fields (Android 14+):** Compose `handwritingDetector`/`handwritingHandler` and View `setHandwritingDelegatorCallback`. Use them so our search box and text boxes accept S Pen writing through the IME for free. ([stylus in text fields](https://developer.android.com/develop/ui/compose/touch-input/stylus-input/stylus-input-in-text-fields))
- **DeX / multi-window / Fold:** Jetpack WindowManager `FoldingFeature` (`HALF_OPENED` + `HORIZONTAL` = tabletop/Flex) plus window size classes. ([fold-aware guide](https://developer.android.com/guide/topics/large-screens/make-apps-fold-aware))

## 5. Smart Board AI

**Vision model:** no public benchmark covers whiteboard photos. On handwritten-document benchmarks (WildHandBench, Aug 2026) the best of 18 models reaches 71.9% against 77.1% for humans, and frontier models degrade on less common scripts. ([WildHandBench](https://arxiv.org/pdf/2608.22959)) General OCR leaderboards put current Claude, Gemini and GPT models close together and change monthly. **Recommendation:** keep ADR-0007 (BYOK, provider modules) and pick the default provider **from our own AI-09 eval set** (Arabic and English boards, glare, perspective), re-run every quarter. Don't hard-code a "best model". Ship Gemini first (cheap, strong multilingual), with Claude and OpenAI-compatible endpoints as peers, so self-hosted servers (Ollama/vLLM) also work.
**On-device (AI-10):** Gemma 3n / Gemma 4 E-series via **LiteRT-LM** (Apache-2.0 runtime, Gemma weights under Google's Gemma terms, which is **not OSI**, so it must be a user-downloaded model, not bundled). ML Kit GenAI Prompt API (Gemini Nano, multimodal) is Play-only, and Galaxy Tab support is unconfirmed. ([LiteRT-LM](https://developers.google.com/edge/litert-lm/overview), [Prompt API](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences))

**Rendering as believable handwriting:**
- **Recommended: "ink font + jitter".** Ship an open handwriting font as **stroke data** (e.g. derived from an OFL single-line/Hershey-style font, or from glyphs the user writes once in a short calibration). Lay out with `:engine:layout`, then add per-glyph variation (baseline wobble, slant, size and spacing noise, pressure curve) and emit real `androidx.ink` strokes. It's deterministic, fast, offline, and Arabic-capable (contextual forms via the font's shaping), and the result is editable ink, not an image.
- **Runner-up / research:** learned synthesis. Graves-style RNN (`sjvasquez/handwriting-synthesis`, license unclear **[verify]**), SDT (CVPR 2023, online trajectories, mainly Chinese), and diffusion models (One-DM MIT, DiffBrush, DiffInk) that output **images**, not strokes. Licenses are unclear, models are trained on IAM (non-commercial terms), and there's no Arabic. Not shippable in 2026.

## 6. Other libraries and architecture

- **Undo/redo:** command pattern over immutable page state (`AddStrokes`, `EraseStrokes(ids)`, `Transform(ids, matrix)`, …), one history per page and merged per gesture. Commands store stroke ids plus the encoded strokes they remove, never page snapshots. Palm rollback (§4) uses the same mechanism.
- **Rendering huge pages / infinite canvas:** committed ink drawn into a tile cache (e.g. 512 px tiles per zoom bucket) with an R-tree/grid spatial index for hit-testing and dirty regions, an LRU cache capped by memory class, and a low-res placeholder while tiles redraw. PDF backgrounds use the same tiles.
- **Search:** keep Room (ADR-0004) but open it with **`BundledSQLiteDriver`** (`androidx.sqlite:sqlite-bundled`). That gives every device the same, modern SQLite instead of whatever version the OEM ships. Use an **FTS5** table (raw SQL, created in a migration callback) with the `trigram` tokenizer for Arabic and partial-word matches. Confirm FTS5 with `PRAGMA compile_options` **[verify]**. Fallback: Room `@Fts4` on the framework SQLite. ([androidx.sqlite](https://developer.android.com/kotlin/multiplatform/sqlite))
- **Image import/crop:** Photo Picker (`PickVisualMedia`, no permission) + SAF for files. For crop, use our own Compose crop UI or uCrop (Apache-2.0). Avoid ML Kit Document Scanner in `foss` (Play Services). For whiteboard perspective correction, OpenCV (Apache-2.0) is heavy (~10 MB/ABI). A small Kotlin homography after the user drags 4 corners is enough.
- **Audio synced to strokes (MEDIA-04):** `MediaRecorder` → AAC/Opus in the `.bnote` package. Stamp each stroke with `audioOffsetMs` at creation, and use Media3 ExoPlayer (Apache-2.0) for seek-on-tap.
- **Backup and sync:** a `.bnote` package as a zip export through SAF (`ACTION_CREATE_DOCUMENT`). For "sync folder" mode, SAF tree URI with file-level last-writer-wins plus a conflict copy. This is Syncthing/Nextcloud-friendly as long as we write atomically (temp file + rename) and never keep a single giant DB file in the synced folder. WebDAV: **`dav4jvm`** (MPL-2.0, which is file-level copyleft and compatible with both Apache-2.0 and GPL-3.0) in a separate opt-in module with `INTERNET`, like the AI providers. ([dav4jvm](https://github.com/bitfireAT/dav4jvm))
- **Also worth adopting:** Baseline Profiles + Macrobenchmark (QA-02), `kotlinx-serialization-protobuf` for the page format next to ink's own stroke encoding, LeakCanary in debug builds, and Android 14+ `Surface.setFrameRate` hints during writing. Avoid Firebase, Crashlytics and any analytics SDK in all flavors (ADR-0003, and F-Droid anti-features).

## Follow-ups proposed
1. Amend **ADR-0005**: `androidx.pdf` primary, PDFium contingency, F-Droid source-build note, MuPDF/`play` conflict.
2. Update **D4** in the roadmap: skip the Samsung Remote SDK; rescope SPEN-11.
3. **REC-01**: include PP-OCRv5 (rendered ink) in the Arabic + English corpus benchmark.
4. **DOC/LIB**: spike `BundledSQLiteDriver` + FTS5 trigram for Arabic search.
