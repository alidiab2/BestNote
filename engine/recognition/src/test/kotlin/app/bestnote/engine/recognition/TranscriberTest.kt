package app.bestnote.engine.recognition

import app.bestnote.core.model.StylusSample
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriberTest {
    private fun stroke(l: Float, t: Float, r: Float, b: Float): InkStrokeSamples =
        listOf(StylusSample(l, t, 0.5f, 0f, 0f, 0), StylusSample(r, b, 0.5f, 0f, 0f, 10))

    /** The Arabic model reads Latin ink as gibberish; the English model reads Arabic ink as a few letters. */
    private class Fake : HandwritingRecognizer {
        override val languages = setOf("ar", "en-US")
        override suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String): List<TextCandidate> {
            val arabicInk = strokes.first().first().x > 0f && strokes.first().first().y < 0f // set by the tests below
            return if (languageTag == "ar") {
                listOf(TextCandidate(if (arabicInk) "عربي" else "alalam", 0f))
            } else {
                listOf(TextCandidate(if (arabicInk) "s" else "hello", 0f))
            }
        }
    }

    private fun arabicStroke(): InkStrokeSamples = listOf(StylusSample(10f, -10f, 0.5f, 0f, 0f, 0), StylusSample(50f, -10f, 0.5f, 0f, 0f, 10))

    @Test fun chooseByScriptWhenOneStrokeGivesNoDirection() = runTest {
        assertEquals("hello", Transcriber(Fake()).transcribe(listOf(stroke(0f, 0f, 50f, 20f))).text)
        assertEquals("عربي", Transcriber(Fake()).transcribe(listOf(arabicStroke())).text)
    }

    @Test fun directionOfDrawingDecidesForLongerInk() = runTest {
        // Four strokes drawn right to left on one line: Arabic, even though both models answer.
        val rtl = (0 until 4).map { i -> stroke(300f - i * 60f, 0f, 340f - i * 60f, 20f) }
        val ltr = (0 until 4).map { i -> stroke(i * 60f, 0f, i * 60f + 40f, 20f) }
        val both = object : HandwritingRecognizer {
            override val languages = setOf("ar", "en-US")
            override suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String) =
                listOf(TextCandidate(if (languageTag == "ar") "مممم" else "text", 0f))
        }
        assertEquals("مممم", Transcriber(both).transcribe(rtl).text)
        assertEquals("text", Transcriber(both).transcribe(ltr).text)
    }

    @Test fun readsLinesTopToBottomAndSkipsEmptyOnes() = runTest {
        val strokes = listOf(stroke(0f, 100f, 50f, 120f), stroke(0f, 0f, 50f, 20f))
        val t = Transcriber(Fake()).transcribe(strokes)
        assertEquals(listOf("hello", "hello"), t.lines)
        assertEquals(true, Transcriber(NoOpHandwritingRecognizer).transcribe(strokes).isEmpty)
    }

    @Test fun corpusDirections() {
        val arabic = Corpus.strokes("arabic")
        val english = Corpus.strokes("lecture_en")
        val arabicLines = InkSegmenter.lines(arabic).map { ReadingChooser.direction(it.strokes.map { i -> arabic[i] }) }
        val englishLines = InkSegmenter.lines(english).map { ReadingChooser.direction(it.strokes.map { i -> english[i] }) }
        println("arabic lines: $arabicLines; english lines: $englishLines")
        assertEquals(listOf(WritingDirection.Rtl, WritingDirection.Rtl), arabicLines)
        assertEquals(true, englishLines.none { it == WritingDirection.Rtl })
        assertEquals(true, englishLines.count { it == WritingDirection.Ltr } >= englishLines.size - 1) // a one-word last line says nothing
    }
}
