/*
 * Debug-only: measures the handwriting recognizer on corpus files (REC-01, REC-10; docs/research/handwriting-recognition-eval.md).
 *
 *   adb shell am broadcast -a app.bestnote.debug.RECOGNIZE -p app.bestnote.debug --es files para_tight.json,arabic.json [--es opts order=0,pre=0]
 *
 * Also: `-a app.bestnote.debug.CLIP` puts files/replay/clip.txt on the clipboard (to paste Arabic into the search box).
 *
 * file(s)  names under files/replay/ (the same place scripts/agent/replay.sh pushes to), comma-separated. The written
 *          text, lines separated by '|', is read from files/replay/<name>.expect.txt (tools/handwriting/corpus has them).
 * opts     TranscribeOptions to measure against the shipped defaults: order=0|1 (writing order), pre=N (pre-context
 *          characters), scale=F (coordinate scale), mixed=0|1 (re-read Latin words of Arabic lines in English),
 *          doubt=F (share of other candidates that must agree on a word).
 * detail   true also prints each language's reading per line and the per-word search index.
 * download true first downloads the missing on-device models (and waits for them).
 *
 * The measured path is the lasso's: Transcriber.transcribe (lines, language choice, recognition) on the whole file.
 * Results go to logcat tag RecEval and files/replay/recognize.txt: one RESULT line per file (WER, CER, lines found,
 * how many wrong words were marked doubtful and how many right ones were) and a TOTAL line.
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
import app.bestnote.engine.recognition.ModelStatus
import app.bestnote.engine.recognition.TranscribeOptions
import app.bestnote.engine.recognition.Transcriber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
        if (intent.action == CLIP) return copyToClipboard(context)
        // A whole corpus takes longer than a receiver may run (ANR after ~10 s): answer at once and work on detached.
        // On a busy device the process may be frozen meanwhile; RecognitionEvalInstrumentation (am instrument) is reliable.
        CoroutineScope(Dispatchers.Default).launch {
            RecognitionEval.evaluate(context.applicationContext) { key -> intent.extras?.get(key)?.toString() }
        }
    }

    /** `--es file x.txt` (UTF-8, under files/replay/) goes to the clipboard, so scripts can paste Arabic with KEYCODE_PASTE. */
    private fun copyToClipboard(context: Context) {
        val text = File(File(context.filesDir, "replay"), "clip.txt").readText().trim()
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("debug", text))
    }

    private companion object {
        const val CLIP = "app.bestnote.debug.CLIP"
    }
}

/** The measurement itself, shared by the broadcast receiver and the instrumentation. */
object RecognitionEval {
    /** Runs the evaluation described by [arg] (files, opts, detail, download); writes recognize.txt and returns the report. */
    suspend fun evaluate(context: Context, arg: (String) -> String?): String {
        val out = StringBuilder()
        try {
            runAll(context, arg, out)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            out.appendLine("error $e")
        }
        File(File(context.filesDir, "replay").apply { mkdirs() }, "recognize.txt").writeText(out.toString())
        out.lineSequence().forEach { Log.i("RecEval", it) }
        return out.toString()
    }

    private class Score(var refWords: Int = 0, var wordErrors: Int = 0, var refChars: Int = 0, var charErrors: Int = 0,
                        var wrong: Int = 0, var wrongFlagged: Int = 0, var right: Int = 0, var rightFlagged: Int = 0) {
        fun add(o: Score) {
            refWords += o.refWords; wordErrors += o.wordErrors; refChars += o.refChars; charErrors += o.charErrors
            wrong += o.wrong; wrongFlagged += o.wrongFlagged; right += o.right; rightFlagged += o.rightFlagged
        }

        override fun toString() = "WER=${"%.3f".format(wordErrors.toDouble() / refWords)} CER=${"%.3f".format(charErrors.toDouble() / refChars)} " +
            "doubt: wrong ${wrongFlagged}/$wrong marked, right ${rightFlagged}/$right marked"
    }

    private suspend fun runAll(context: Context, arg: (String) -> String?, out: StringBuilder) {
        val entry = EntryPointAccessors.fromApplication(context, RecognitionEvalEntryPoint::class.java)
        val recognizer = entry.recognizer()
        val models = entry.models()
        (models as? app.bestnote.engine.recognition.mlkit.MlKitModels)?.refresh()
        if (arg("download") == "true" && !models.state.value.isReady) {
            models.download()
            val done = withTimeoutOrNull(DOWNLOAD_MS) { models.state.first { it.status == ModelStatus.Ready || it.status == ModelStatus.Failed } }
            out.appendLine("download: ${done ?: "timed out"}")
        }
        val opts = arg("opts").orEmpty().split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val defaults = TranscribeOptions()
        val options = TranscribeOptions(
            writingOrder = opts["order"]?.let { it == "1" } ?: defaults.writingOrder,
            preContext = opts["pre"]?.toInt() ?: defaults.preContext,
            scale = opts["scale"]?.toFloat() ?: defaults.scale,
            mixedWords = opts["mixed"]?.let { it == "1" } ?: defaults.mixedWords,
            doubtAgreement = opts["doubt"]?.toFloat() ?: defaults.doubtAgreement,
        )
        out.appendLine("== options $options, languages ready ${recognizer.languages}")
        val names = (arg("files") ?: arg("file")).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val total = Score()
        for (name in names) {
            val score = run(context, name, recognizer, options, arg("detail") == "true", out) ?: continue
            total.add(score)
        }
        out.appendLine("TOTAL ${names.size} files words=${total.refWords} $total")
    }

    private suspend fun run(
        context: Context,
        name: String,
        recognizer: HandwritingRecognizer,
        options: TranscribeOptions,
        detail: Boolean,
        out: StringBuilder,
    ): Score? {
        val dir = File(context.filesDir, "replay")
        val expect = File(dir, name.removeSuffix(".json") + ".expect.txt").takeIf { it.isFile }?.readText().orEmpty().trim()
        val file = StrokeFile.parse(File(dir, name).readText())
        val strokes: List<InkStrokeSamples> = file.strokes.filter { !it.hover }.map { s ->
            val t0 = s.samples.first().tMs
            s.samples.map { StylusSample(it.x * PT_PER_MM, it.y * PT_PER_MM, it.pressure, it.tiltRad, it.orientationRad, (it.tMs - t0).toLong()) }
        }
        out.appendLine("== $name: ${strokes.size} strokes")
        val langs = listOf("ar", "en-US")
        if (detail) {
            for ((i, line) in InkSegmenter.lines(strokes).withIndex()) {
                val ink = line.strokes.map { strokes[it] }
                val per = langs.map { tag -> "$tag=${recognizer.recognize(ink, tag).take(3).map { "'${it.text}' ${it.score}" }}" }
                out.appendLine("line ${i + 1}: ${per.joinToString(" | ")}")
            }
            PageTextExtractor(recognizer, langs).ink(strokes).forEach { out.appendLine("word '${it.text}' <- ${it.folded}") }
        }

        // The lasso path: the whole selection through the Transcriber.
        val t0 = SystemClock.elapsedRealtime()
        val transcript = Transcriber(recognizer, langs, options).transcribe(strokes)
        val ms = SystemClock.elapsedRealtime() - t0
        transcript.lines.forEach { out.appendLine("read: $it") }
        if (expect.isBlank()) return null
        val expected = expect.split('|')
        expected.forEach { out.appendLine("want: $it") }

        // Words of the reading, each with whether it was marked doubtful.
        val text = transcript.text
        val got = ArrayList<Pair<String, Boolean>>()
        Regex("\\S+").findAll(text).forEach { m ->
            val flagged = transcript.doubtful.any { it.first <= m.range.last && m.range.first <= it.last }
            norm(m.value).split(' ').filter { it.isNotEmpty() }.forEach { got += it to flagged }
        }
        val ref = expected.flatMap { norm(it).split(' ') }.filter { it.isNotEmpty() }
        val score = Score(refWords = ref.size, wordErrors = edit(ref, got.map { it.first }))
        val refChars = ref.joinToString(" ").toList()
        score.refChars = refChars.size
        score.charErrors = edit(refChars, got.joinToString(" ") { it.first }.toList())
        val correct = matched(ref, got.map { it.first })
        got.forEachIndexed { i, (_, flagged) ->
            if (i in correct) { score.right++; if (flagged) score.rightFlagged++ } else { score.wrong++; if (flagged) score.wrongFlagged++ }
        }
        out.appendLine("RESULT $name lines=${transcript.lines.size}/${expected.size} words=${ref.size} $score ms=$ms")
        return score
    }

    /** Indices of [b] that an optimal alignment with [a] matches to an equal word. */
    private fun matched(a: List<String>, b: List<String>): Set<Int> {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        val out = HashSet<Int>()
        var i = a.size
        var j = b.size
        while (i > 0 && j > 0) {
            when {
                a[i - 1] == b[j - 1] && d[i][j] == d[i - 1][j - 1] -> { out += j - 1; i--; j-- }
                d[i][j] == d[i - 1][j - 1] + 1 -> { i--; j-- }
                d[i][j] == d[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        return out
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

    private const val PT_PER_MM = 72f / 25.4f
    private const val DOWNLOAD_MS = 600_000L
}
