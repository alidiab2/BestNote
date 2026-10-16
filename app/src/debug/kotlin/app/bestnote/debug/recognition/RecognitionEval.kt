/*
 * Debug-only: measures the handwriting recognizer on a corpus file (REC-01, docs/research/handwriting-recognition-eval.md).
 *
 *   adb shell am broadcast -a app.bestnote.debug.RECOGNIZE -p app.bestnote.debug --es file arabic.json
 *
 * file    name under files/replay/ (the same place scripts/agent/replay.sh pushes to). The written text,
 *         lines separated by '|', is read from files/replay/<name>.expect.txt when present.
 *
 * Results (per word, per line, summary with timings) go to logcat tag RecEval and files/replay/recognize.txt.
 */
package app.bestnote.debug.recognition

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import app.bestnote.core.common.SearchText
import app.bestnote.core.model.StylusSample
import app.bestnote.debug.replay.StrokeFile
import app.bestnote.engine.recognition.HandwritingModels
import app.bestnote.engine.recognition.HandwritingRecognizer
import app.bestnote.engine.recognition.InkSegmenter
import app.bestnote.engine.recognition.InkStrokeSamples
import app.bestnote.engine.recognition.PageTextExtractor
import app.bestnote.engine.recognition.Transcriber
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecognitionEvalEntryPoint {
    fun recognizer(): HandwritingRecognizer
    fun models(): HandwritingModels
}

class RecognitionEvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val out = StringBuilder()
            try {
                run(context, intent.getStringExtra("file").orEmpty(), out)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                out.appendLine("error ${e}")
            }
            File(File(context.filesDir, "replay").apply { mkdirs() }, "recognize.txt").writeText(out.toString())
            out.lineSequence().forEach { Log.i("RecEval", it) }
            pending.finish()
        }
    }

    private suspend fun run(context: Context, name: String, out: StringBuilder) {
        val entry = EntryPointAccessors.fromApplication(context, RecognitionEvalEntryPoint::class.java)
        val recognizer = entry.recognizer()
        (entry.models() as? app.bestnote.engine.recognition.mlkit.MlKitModels)?.refresh()
        val expect = File(File(context.filesDir, "replay"), name.removeSuffix(".json") + ".expect.txt").takeIf { it.isFile }?.readText().orEmpty().trim()
        val file = StrokeFile.parse(File(File(context.filesDir, "replay"), name).readText())
        val strokes: List<InkStrokeSamples> = file.strokes.filter { !it.hover }.map { s ->
            val t0 = s.samples.first().tMs
            s.samples.map { StylusSample(it.x * PT_PER_MM, it.y * PT_PER_MM, it.pressure, it.tiltRad, it.orientationRad, (it.tMs - t0).toLong()) }
        }
        out.appendLine("== $name: ${strokes.size} strokes, languages ready ${recognizer.languages}")
        val langs = listOf("ar", "en-US")

        // Per line: each language's best reading and cost, and what the Transcriber picks.
        val lines = InkSegmenter.lines(strokes)
        val transcriber = Transcriber(recognizer)
        val lineTexts = ArrayList<String>()
        var lineMs = 0L
        for ((i, line) in lines.withIndex()) {
            val ink = line.strokes.map { strokes[it] }
            val per = langs.map { tag ->
                val t = SystemClock.elapsedRealtime()
                val c = recognizer.recognize(ink, tag).firstOrNull()
                lineMs += SystemClock.elapsedRealtime() - t
                "$tag='${c?.text}' score=${c?.score}"
            }
            val picked = transcriber.readLine(ink)
            lineTexts += picked
            out.appendLine("line ${i + 1}: ${per.joinToString(" | ")} => '$picked'")
        }

        // Per word (what the search index stores).
        val extractor = PageTextExtractor(recognizer, langs)
        val t0 = SystemClock.elapsedRealtime()
        val words = extractor.ink(strokes)
        val wordMs = SystemClock.elapsedRealtime() - t0
        words.forEach { out.appendLine("word '${it.text}' <- ${it.folded}") }

        if (expect.isNotBlank()) {
            val expected = expect.split('|').map { norm(it) }
            val got = lineTexts.map { norm(it) }
            val refWords = expected.joinToString(" ").split(' ').filter { it.isNotEmpty() }
            val gotWords = got.joinToString(" ").split(' ').filter { it.isNotEmpty() }
            val wer = edit(refWords, gotWords).toDouble() / refWords.size
            val refChars = expected.joinToString(" ").toList()
            val cer = edit(refChars, got.joinToString(" ").toList()).toDouble() / refChars.size
            val indexed = words.joinToString(" ") { it.folded }
            val searchable = refWords.filter { it.length >= 3 }.distinct()
            val found = searchable.count { it in indexed }
            out.appendLine("expected: ${expected.joinToString(" / ")}")
            out.appendLine("RESULT $name lines=${lines.size} words=${words.size} lineCER=${"%.2f".format(cer)} lineWER=${"%.2f".format(wer)} " +
                "searchRecall=$found/${searchable.size} lineMs=$lineMs wordMs=$wordMs")
        }
    }

    private fun norm(s: String) = SearchText.fold(s.replace(Regex("[\\p{P}\\p{S}…—]"), " "))

    private fun <T> edit(a: List<T>, b: List<T>): Int {
        var prev = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val cur = IntArray(b.size + 1)
            cur[0] = i
            for (j in 1..b.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.size]
    }

    private companion object {
        const val PT_PER_MM = 72f / 25.4f
    }
}
