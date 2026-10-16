# Handwriting recognition: ML Kit Digital Ink on the corpus (REC-01)

Decision (ADR-0006): ship **ML Kit Digital Ink Recognition** with on-device models for `ar` and `en-US`, downloaded on demand
(about 50 MB for both) and used offline after that. Open models were not evaluated here; the `HandwritingRecognizer`
interface stays the seam for a `foss` engine (QA-07).

## How it was measured
`app/src/debug/.../RecognitionEval.kt` (debug builds only) reads a corpus file from `tools/handwriting/corpus` and prints, per
line and per word, what each language's model read, plus CER/WER against the text that was written:

```bash
adb push tools/handwriting/corpus/lecture_en.json /data/local/tmp/ && adb shell run-as <pkg> cp /data/local/tmp/lecture_en.json files/replay/
# put the written text (lines separated by "|") in files/replay/lecture_en.expect.txt
adb shell am broadcast -a app.bestnote.debug.RECOGNIZE -p <pkg> --es file lecture_en.json   # then: adb logcat -s RecEval
```

Device: the `AuraTablet` x86 emulator (software GPU, 2 GB), so latencies are an upper bound for a real tablet.
Corpus: synthetic strokes (`tools/handwriting`), not real hands.

## Results

| File | Script | Strokes | Lines / words found | Line CER | Line WER | Word search recall¹ | Time, lines (2 models) | Time, words (2 models) |
|---|---|---|---|---|---|---|---|---|
| `lecture_en` | English, joined cursive paragraph | 124 | 9 / 35 | **0.01** | **0.06** | 31 / 31 | 1.1 s | 1.0 s |
| `arabic` | Arabic Ruqaa-style, 2 lines | 40 | 2 / 8 | 0.50 | 1.00 | 1 / 7 | 0.3 s | 0.2 s |

¹ Of the written words with 3+ letters, how many appear in some candidate of the indexed words (what search matches on).

Reading the numbers:
- **English is excellent**: the line reading is almost exact ("Lecture 7-Kinematics. Motion with", "label both ares before you
  calculate"), and every written word is found in the candidates.
- **Arabic is weak on this corpus.** Lines come back as plausible Arabic ("تاكول تونس الشالي" for "قانون نيوتن الثاني"), but few words
  are right. The corpus glyphs are a stylized Ruqaa unlike natural handwriting, so this is a lower bound; it needs a check with real
  writers before anyone promises Arabic search quality.
- One page of dense text costs about 1 s of recognition (35 words, two models) on the emulator.

## What the evaluation changed in the design
1. **ML Kit returns no confidence** (candidate scores are absent), so the "most confident language wins" rule cannot work. The
   models also read the *wrong* script's ink as gibberish (Arabic model on English: "ممممممممممم", "alaalamalgahan"; English model on
   Arabic: "s", "de"). `ReadingChooser` decides the language from how the ink was written: Arabic runs right to left, so successive
   strokes move left (all corpus lines classify correctly, English except a one-word last line). Single words fall back to "the Arabic
   model read Arabic letters that are not one repeated letter".
2. **Search keeps the top 3 candidates of both models per word**, folded (case, accents, Arabic diacritics, alef/ya/ta-marbuta
   variants). The best reading is only what the result shows; matching uses all of them ("acceleration" is the 4th candidate of a
   word whose first candidate is "aelaalam").
3. Words are cut with a geometric segmenter (`InkSegmenter`): lines by stroke centers, tiny strokes (dots, i-dots) attach to the
   line they sit on, words by horizontal gaps. 9 of 9 lecture lines, 2 of 2 Arabic lines.
