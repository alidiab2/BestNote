package app.bestnote.engine.recognition

import app.bestnote.core.common.SearchText

/**
 * The text of some handwriting, read line by line.
 * @property doubtful character ranges of [text] holding words the recognizer was unsure of (REC-10), so the
 *   confirmation can mark them for a quick fix.
 */
data class Transcript(val lines: List<String>, val doubtful: List<IntRange> = emptyList()) {
    val text: String get() = lines.joinToString("\n")
    val isEmpty: Boolean get() = lines.all { it.isBlank() }
}

/**
 * How [Transcriber] prepares ink for the recognizer (REC-10). The defaults are what the app ships; the debug
 * evaluation (`RecognitionEval`) switches them one by one to measure what each is worth.
 * @property writingOrder reorders each line's strokes along the writing direction of the language being read,
 *   so ink that is not in drawing order (moved words, undo) reads as written.
 * @property preContext how many characters of the previous line go to the recognizer as context (0 = none).
 * @property scale multiplies coordinates before recognizing: page points → roughly the screen pixels the recognizer
 *   was trained on (measured: slightly better than raw points for mixed and Arabic lines).
 * @property mixedWords in a right-to-left line, re-reads the words the Arabic model gave in Latin letters or digits
 *   with the left-to-right language (an English word or a number inside an Arabic sentence).
 * @property doubtAgreement share of the recognizer's other candidates that must contain a word for it not to be
 *   marked doubtful ([Doubt]).
 */
data class TranscribeOptions(
    val writingOrder: Boolean = true,
    val preContext: Int = 20,
    val scale: Float = SCREEN_SCALE,
    val mixedWords: Boolean = true,
    val doubtAgreement: Float = Doubt.AGREE,
) {
    companion object {
        const val SCREEN_SCALE = 2.5f
    }
}

/**
 * Handwriting → text for a lasso selection (REC-05, REC-10). The ink is cut into lines ([InkSegmenter]); each line
 * is read in every language and the reading in the script it was written in wins ([ReadingChooser]); the result
 * keeps the page's own line breaks.
 */
class Transcriber(
    private val recognizer: HandwritingRecognizer,
    private val languages: List<String> = listOf("ar", "en-US"),
    private val options: TranscribeOptions = TranscribeOptions(),
) {
    /** One line's reading: the text, which language read it, and which words of it are doubtful. */
    data class LineReading(val text: String, val language: String?, val doubtful: List<IntRange>)

    /** [only] reads every line in that one language (a user override of the automatic choice). */
    suspend fun transcribe(strokes: List<InkStrokeSamples>, only: String? = null): Transcript {
        val lines = InkSegmenter.lines(strokes)
        val out = ArrayList<String>()
        val doubtful = ArrayList<IntRange>()
        var offset = 0
        var previous: LineReading? = null
        for (line in lines) {
            val reading = readLine(strokes, line, previous, only)
            if (reading.text.isBlank()) continue
            reading.doubtful.forEach { doubtful += (it.first + offset)..(it.last + offset) }
            out += reading.text
            offset += reading.text.length + 1
            previous = reading
        }
        return Transcript(out, doubtful)
    }

    /** The best reading of one line's [ink] (in drawing order) over all languages the recognizer has. */
    suspend fun readLine(ink: List<InkStrokeSamples>, only: String? = null): String {
        val points = ink.flatten()
        if (points.isEmpty()) return ""
        val box = app.bestnote.core.model.PageRect(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        return readLine(ink, InkSegment(ink.indices.toList(), box), null, only).text
    }

    private suspend fun readLine(
        strokes: List<InkStrokeSamples>,
        line: InkSegment,
        previous: LineReading?,
        only: String?,
    ): LineReading {
        val drawn = line.strokes.map { strokes[it] }
        val direction = ReadingChooser.direction(drawn)
        val candidates = LinkedHashMap<String, List<TextCandidate>>()
        for (tag in languages) {
            if (only != null && tag != only) continue
            if (recognizer.languages.isNotEmpty() && tag !in recognizer.languages) continue
            val rtl = ReadingChooser.isRtl(tag)
            val order = if (options.writingOrder) InkSegmenter.writingOrder(strokes, line.strokes, rtl) else line.strokes
            val ink = order.map { scaled(strokes[it]) }
            val context = previous?.takeIf { options.preContext > 0 && it.language != null && ReadingChooser.isRtl(it.language) == rtl }
                ?.text?.takeLast(options.preContext).orEmpty()
            candidates[tag] = recognizer.recognize(ink, tag, InkHints(context)).filter { it.text.isNotBlank() }
        }
        val picked = ReadingChooser.pick(candidates.mapValues { it.value.firstOrNull() }, direction) ?: return LineReading("", null, emptyList())
        var text = picked.second.text.trim()
        if (options.mixedWords && ReadingChooser.isRtl(picked.first)) text = readLatinWords(strokes, line, text, only)
        return LineReading(text, picked.first, Doubt.ranges(text, candidates[picked.first].orEmpty().drop(1).map { it.text }, options.doubtAgreement))
    }

    /**
     * A right-to-left reading with words in Latin letters or digits: the Arabic model has met an English word or a number
     * and guessed at it. When the reading has one word per word of ink, those words are read again in the
     * left-to-right language, each on its own ink.
     */
    private suspend fun readLatinWords(strokes: List<InkStrokeSamples>, line: InkSegment, text: String, only: String?): String {
        val tokens = text.split(' ').filter { it.isNotEmpty() }
        if (tokens.none(::isLatinWord)) return text
        val ltr = languages.firstOrNull { !ReadingChooser.isRtl(it) && (only == null || it == only) && (recognizer.languages.isEmpty() || it in recognizer.languages) }
            ?: return text
        val words = InkSegmenter.words(strokes, line).asReversed() // right to left, as the reading runs
        if (words.size != tokens.size) return text
        return tokens.mapIndexed { i, token ->
            if (!isLatinWord(token)) return@mapIndexed token
            val order = if (options.writingOrder) InkSegmenter.writingOrder(strokes, words[i].strokes, rtl = false) else words[i].strokes
            val read = recognizer.recognize(order.map { scaled(strokes[it]) }, ltr).firstOrNull()?.text?.trim().orEmpty()
            if (read.isNotEmpty() && !SearchText.isArabic(read)) read else token
        }.joinToString(" ")
    }

    private fun isLatinWord(token: String) = token.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    private fun scaled(stroke: InkStrokeSamples): InkStrokeSamples =
        if (options.scale == 1f) stroke else stroke.map { it.copy(x = it.x * options.scale, y = it.y * options.scale) }
}

/**
 * Which words of a reading the recognizer was unsure of (REC-10). ML Kit gives no usable scores, but its other
 * candidates tell: a word that most alternatives agree on is solid, one they disagree on is a guess.
 */
object Doubt {
    const val AGREE = 0.5f
    private const val ALTERNATIVES = 4

    /** Ranges of [text] (whitespace-separated words) that fewer than [agreement] of [alternatives] also contain. */
    fun ranges(text: String, alternatives: List<String>, agreement: Float = AGREE): List<IntRange> {
        val others = alternatives.take(ALTERNATIVES).map { alt -> alt.split(' ').filter { it.isNotEmpty() }.toSet() }
        if (others.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        Regex("\\S+").findAll(text).forEach { m ->
            val agree = others.count { m.value in it }
            if (agree < agreement * others.size) out += m.range
        }
        return out
    }
}
