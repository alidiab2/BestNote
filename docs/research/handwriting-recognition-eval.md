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

# Convert to text: the whole lasso path (REC-10)

"Convert to text is very bad." REC-01 measured one line at a time on two clean files. REC-10 measures what the lasso
actually does (`Transcriber.transcribe` on the whole selection: line segmentation, stroke order, language choice per
line, ML Kit) on harder ink, then changes one thing at a time and keeps only what the numbers support.

## Corpus added (`tools/handwriting`, each with `<name>.expect.txt`)
| File | What makes it hard |
|---|---|
| `para_tight` | 8 lines of cursive notes on tight ruling (2.5 x-heights): descenders touch the next line's ascenders |
| `slanted` | letters slanted 14°, every line climbing 4° |
| `numbers` | print digits and punctuation: `9:30,` `12-15.` `555-0142` `3 x 7 = 21?` |
| `arabic_para` | 4 Arabic lines on 12 mm ruling, dots and marks written after each word |
| `mixed` | Arabic lines with an English word or a number inside (`قانون Newton الثاني`, `السرعة تساوي 20 متر`) |
| `para_moved`, `arabic_moved` | the same paragraphs after editing: every 4th (3rd) word's strokes moved to the end of the stroke list, which is what the page holds after a lasso move or an erase + undo, and what the lasso hands to Convert |

`lecture_en`, `arabic`, `tiny_fast`, `slow_large` got expect files too (276 words in all). Synthetic ink, not real hands.

## How to measure
```bash
# corpus files + expect files under files/replay/ of the package, then (am instrument survives a busy emulator):
adb shell am instrument -w -e files para_tight.json,mixed.json -e opts order=0 <pkg>/app.bestnote.debug.recognition.RecognitionEvalInstrumentation
```
`opts` switches one `TranscribeOptions` knob (`order`, `pre`, `scale`, `mixed`, `doubt`); the report has WER/CER per file and
how many wrong/right words the confirmation would underline. Emulator `AuraTablet`, ML Kit `ar` + `en-US`.

## Causes found, in order of damage
1. **Line segmentation merged and split lines.** Lines were clusters of stroke centres with a gap threshold, so tight
   ruling merged lines (`para_tight` 6 of 8 lines, `tiny_fast` 2 of 3), a climbing line split in two (`slanted` 6 of 5),
   and Arabic marks and dashes became lines of their own (`arabic_para` 5 of 4, `mixed` 5 of 3). Two merged lines go
   to ML Kit as one: garbage. Now: strokes drawn one after another side by side on the same band form runs; runs join
   the line whose ink *near them* shares their band, never stacking on ink the line already has; small strokes go with
   the letter written just before or after them. Every corpus file now splits into exactly the written lines
   (`SegmentationCorpusTest`).
2. **Strokes out of writing order.** The lasso returns strokes in z-order; after moving a word or undoing an erase
   its strokes come last, and ML Kit reads ink in the order given (`para_moved`: WER 0.39 although every line was
   found). Strokes have no creation time in the model and samples carry per-stroke time only, so the order is rebuilt
   from the stroke list: where the list skips (ink added later), pieces are put back along the line in the writing
   direction of the language being read. Ink drawn straight on is never reordered (an English word inside an Arabic
   line keeps its left-to-right strokes); `inkWrittenInOrderKeepsItsOrder` checks every in-order file.
3. **English words and numbers inside Arabic lines.** The line's script is Arabic, so the Arabic reading wins, and the
   Arabic model writes Latin junk for them (`Analen` for *Newton*, `00` for *20*). When the Arabic reading has words in
   Latin letters or digits and one word per word of ink, those words are read again by the English model.
4. **No context between lines.** The previous line's last 20 characters now go to ML Kit as pre-context (same script only).
5. **Coordinates in page points.** Scaling ×2.5 (about screen pixels) is slightly better for mixed/Arabic lines.

Tried and dropped (no gain): the line's writing band (line width × line pitch) as `WritingArea` instead of the ink box
(English unchanged, Arabic worse: `arabic` WER 0.75 → 1.00); air time between strokes from pen travel instead of a fixed
100 ms (WER +0.003). Language choice per line (`ReadingChooser`) picked the right script on every line once lines were right.

## Before / after (WER / CER, full lasso path)
| File | Words | Before (main) | After | Lines found before → after |
|---|---|---|---|---|
| `lecture_en` | 50 | 0.060 / 0.010 | 0.060 / 0.010 | 9/9 → 9/9 |
| `tiny_fast` | 28 | 0.036 / 0.009 | 0.036 / 0.009 | 2/3 → 3/3 |
| `slow_large` | 5 | 0.000 / 0.000 | 0.000 / 0.000 | 2/2 → 2/2 |
| `para_tight` | 51 | 0.059 / 0.026 | **0.000 / 0.000** | 6/8 → 8/8 |
| `slanted` | 19 | 0.211 / 0.127 | **0.000 / 0.000** | 6/5 → 5/5 |
| `numbers` | 18 | 0.222 / 0.063 | 0.167 / 0.047 | 3/3 → 3/3 |
| `para_moved` | 51 | 0.392 / 0.347 | **0.000 / 0.000** | 6/8 → 8/8 |
| `arabic` | 8 | 1.000 / 0.500 | 1.000 / 0.500 | 2/2 → 2/2 |
| `arabic_para` | 17 | 0.647 / 0.200 | 0.588 / 0.189 | 5/4 → 4/4 |
| `arabic_moved` | 17 | 0.765 / 0.389 | 0.588 / 0.189 | 5/4 → 4/4 |
| `mixed` | 12 | 0.833 / 0.381 | 0.500 / 0.143 | 5/3 → 3/3 |
| **All** | 276 | **0.279 / 0.158** | **0.149 / 0.053** | |

What each kept change is worth (total WER, each measured against the others on): segmentation 0.279 → 0.246;
writing order 0.236 → 0.152; pre-context 0.156 → 0.152; Latin words in Arabic lines 0.167 → 0.159; scale 0.152 → 0.149.

English is now near exact on all English files. Arabic stays weak on this synthetic Ruqaa-style ink (the glyphs are
unlike real hands; REC-01 already called it a lower bound) and needs real Arabic writers to judge; "Convert with AI"
remains the fallback for it.

## Doubtful words in the confirmation
ML Kit gives no usable scores, so a word of the reading is marked doubtful when fewer than half of the model's other
candidates contain it. The confirmation underlines those words (an edit removes the underline of the word it touches)
and points to "Convert with AI" when a provider is set up. On the corpus: 8 of 38 wrong words are underlined and 20 of
235 right ones (3 in 10 underlines are real errors, against 1 in 7 of all words); requiring 75% agreement catches
17 of 41 but underlines 50 right words, so 50% ships. It is a hint, not a spellchecker.
