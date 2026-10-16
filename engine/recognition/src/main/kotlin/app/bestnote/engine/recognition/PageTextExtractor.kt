package app.bestnote.engine.recognition

import app.bestnote.core.common.SearchText
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.SegmentKind
import app.bestnote.core.model.TextElement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** One searchable piece of a page, ready to store: [text] is shown, [folded] is matched. */
data class SegmentDraft(val kind: SegmentKind, val text: String, val folded: String, val rect: PageRect)

/**
 * Turns a page's handwriting and typed text into [SegmentDraft]s (REC-02). Handwriting is cut into words
 * ([InkSegmenter]); each word is read in every language and all the top readings are kept for matching, so a
 * misread "Newlon" still finds "Newton" through its other candidates. The best reading is what the result shows.
 */
class PageTextExtractor(
    private val recognizer: HandwritingRecognizer,
    private val languages: List<String> = listOf("ar", "en-US"),
    private val candidatesPerLanguage: Int = 3,
) {
    suspend fun ink(strokes: List<InkStrokeSamples>): List<SegmentDraft> {
        val out = ArrayList<SegmentDraft>()
        for (word in InkSegmenter.words(strokes)) {
            val ink = word.strokes.map { strokes[it] }
            val readings = ArrayList<TextCandidate>()
            for (tag in languages) {
                readings += recognizer.recognize(ink, tag).filter { it.text.isNotBlank() }.take(candidatesPerLanguage)
            }
            val best = readings.minByOrNull { it.score } ?: continue
            val folded = readings.map { SearchText.fold(it.text) }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
            if (folded.isEmpty()) continue
            out += SegmentDraft(SegmentKind.Ink, best.text.trim(), folded, word.box)
        }
        return out
    }

    /** Typed text boxes: one segment each, boxed by the frame (turned boxes by the box around the turned frame). */
    fun elements(elements: List<PageElement>): List<SegmentDraft> = elements.filterIsInstance<TextElement>().mapNotNull { t ->
        val folded = SearchText.fold(t.text)
        if (folded.isEmpty()) return@mapNotNull null
        val c = abs(cos(t.rotation)); val s = abs(sin(t.rotation))
        val halfW = (t.width * c + t.height * s) / 2f
        val halfH = (t.width * s + t.height * c) / 2f
        SegmentDraft(SegmentKind.Text, t.text.trim().lineSequence().joinToString(" "), folded, PageRect(t.cx - halfW, t.cy - halfH, t.cx + halfW, t.cy + halfH))
    }
}
