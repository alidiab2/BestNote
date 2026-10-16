package app.bestnote.engine.recognition

import app.bestnote.core.model.StylusSample
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriberTest {
    private fun stroke(l: Float, t: Float, r: Float, b: Float): InkStrokeSamples =
        listOf(StylusSample(l, t, 0.5f, 0f, 0f, 0), StylusSample(r, b, 0.5f, 0f, 0f, 10))

    /** Answers "عربي" for ink on the first line in Arabic and "hello" in English, with the given costs. */
    private class Fake(val arabicScore: Float, val englishScore: Float) : HandwritingRecognizer {
        override val languages = setOf("ar", "en-US")
        override suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String) =
            if (languageTag == "ar") listOf(TextCandidate("عربي", arabicScore)) else listOf(TextCandidate("hello", englishScore))
    }

    @Test fun keepsTheMoreConfidentLanguagePerLine() = runTest {
        val strokes = listOf(stroke(0f, 0f, 50f, 20f))
        assertEquals("hello", Transcriber(Fake(arabicScore = 9f, englishScore = 2f)).transcribe(strokes).text)
        assertEquals("عربي", Transcriber(Fake(arabicScore = 1f, englishScore = 2f)).transcribe(strokes).text)
    }

    @Test fun readsLinesTopToBottomAndSkipsEmptyOnes() = runTest {
        val strokes = listOf(stroke(0f, 100f, 50f, 120f), stroke(0f, 0f, 50f, 20f))
        val t = Transcriber(Fake(9f, 2f)).transcribe(strokes)
        assertEquals(listOf("hello", "hello"), t.lines)
        assertEquals(true, Transcriber(NoOpHandwritingRecognizer).transcribe(strokes).isEmpty)
    }
}
