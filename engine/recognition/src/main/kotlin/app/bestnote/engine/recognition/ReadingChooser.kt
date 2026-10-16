package app.bestnote.engine.recognition

import app.bestnote.core.common.SearchText
import kotlin.math.sqrt

/** Which way a piece of handwriting runs. */
enum class WritingDirection { Ltr, Rtl, Unknown }

/**
 * Decides which language's reading of some ink to trust (REC-01). ML Kit gives no usable confidence (its scores
 * are absent), and the Arabic model happily reads Latin ink as Arabic or Latin gibberish and vice versa. What does
 * separate the scripts is how people write them: Arabic runs right to left, so successive strokes move left; Latin
 * runs the other way. Short pieces of ink (a single word) don't show a trend, so those fall back to the text:
 * a reading in Arabic letters that isn't one repeated letter wins for the Arabic model.
 */
object ReadingChooser {
    private const val TREND = 0.3f
    private const val MIN_STROKES = 4
    private const val MARK_SIZE = 0.35f

    /** The writing direction of [strokes] (in drawing order), from the drift of the strokes' centers. */
    fun direction(strokes: List<InkStrokeSamples>): WritingDirection {
        fun size(s: InkStrokeSamples) = maxOf(s.maxOf { it.x } - s.minOf { it.x }, s.maxOf { it.y } - s.minOf { it.y })
        val drawn = strokes.filter { it.isNotEmpty() }
        val sizes = drawn.map(::size).sorted()
        if (sizes.isEmpty()) return WritingDirection.Unknown
        // Dots and marks are added after the word, whichever way it runs: leave them out.
        val letters = drawn.filter { size(it) >= MARK_SIZE * sizes[((sizes.size - 1) * 0.75f).toInt()] }
        if (letters.size < MIN_STROKES) return WritingDirection.Unknown
        val xs = letters.map { s -> (s.minOf { it.x } + s.maxOf { it.x }) / 2f }
        val r = correlation(xs.indices.map { it.toFloat() }, xs)
        return when {
            r <= -TREND -> WritingDirection.Rtl
            r >= TREND -> WritingDirection.Ltr
            else -> WritingDirection.Unknown
        }
    }

    private fun correlation(a: List<Float>, b: List<Float>): Float {
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var cov = 0f; var va = 0f; var vb = 0f
        for (i in a.indices) {
            cov += (a[i] - ma) * (b[i] - mb)
            va += (a[i] - ma) * (a[i] - ma)
            vb += (b[i] - mb) * (b[i] - mb)
        }
        return if (va == 0f || vb == 0f) 0f else cov / sqrt(va * vb)
    }

    /** True for BCP-47 tags of right-to-left languages the recognizer reads. */
    fun isRtl(tag: String) = tag.substringBefore('-') in setOf("ar", "fa", "he", "ur")

    /**
     * The reading to show among [readings] (top candidate per language tag, in the order of preference for ties).
     * @return the winning tag and candidate, or null when nothing was read.
     */
    fun pick(readings: Map<String, TextCandidate?>, direction: WritingDirection): Pair<String, TextCandidate>? {
        val read = readings.filterValues { it != null && it.text.isNotBlank() }.mapValues { it.value!! }
        if (read.isEmpty()) return null
        val rtl = read.filterKeys(::isRtl)
        val ltr = read.filterKeys { !isRtl(it) }
        fun first(m: Map<String, TextCandidate>) = m.entries.minByOrNull { it.value.score }?.let { it.key to it.value }
        return when (direction) {
            WritingDirection.Rtl -> first(rtl) ?: first(ltr)
            WritingDirection.Ltr -> first(ltr) ?: first(rtl)
            WritingDirection.Unknown -> {
                val arabic = rtl.entries.firstOrNull { plausibleArabic(it.value.text) }
                if (arabic != null) arabic.key to arabic.value else first(ltr) ?: first(rtl)
            }
        }
    }

    /** Arabic letters, and not one letter over and over. */
    internal fun plausibleArabic(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val share = letters.count { SearchText.isArabic(it.toString()) }.toFloat() / letters.length
        return share >= 0.5f && letters.toSet().size > 1
    }
}
