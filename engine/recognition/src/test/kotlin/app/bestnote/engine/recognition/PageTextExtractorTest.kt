package app.bestnote.engine.recognition

import app.bestnote.core.model.SegmentKind
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.TextElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextExtractorTest {
    private fun stroke(l: Float, r: Float): InkStrokeSamples =
        listOf(StylusSample(l, 0f, 0.5f, 0f, 0f, 0), StylusSample(r, 20f, 0.5f, 0f, 0f, 10))

    /** English reads "Newlon"/"Newton", Arabic reads garbage with a worse cost. */
    private val recognizer = object : HandwritingRecognizer {
        override val languages = setOf("ar", "en-US")
        override suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String) =
            if (languageTag == "en-US") listOf(TextCandidate("Newlon", 1f), TextCandidate("Newton", 2f)) else listOf(TextCandidate("ؤؤ", 9f))
    }

    @Test fun everyWordKeepsAllCandidatesForMatching() = runTest {
        val words = PageTextExtractor(recognizer).ink(listOf(stroke(0f, 40f), stroke(100f, 140f)))
        assertEquals(2, words.size)
        assertEquals("Newlon", words[0].text)
        assertTrue("newton" in words[0].folded && "newlon" in words[0].folded)
        assertEquals(SegmentKind.Ink, words[0].kind)
        assertEquals(0f, words[0].rect.left, 0.01f)
        assertEquals(140f, words[1].rect.right, 0.01f)
    }

    @Test fun unreadableWordsAreSkipped() = runTest {
        assertTrue(PageTextExtractor(NoOpHandwritingRecognizer).ink(listOf(stroke(0f, 40f))).isEmpty())
    }

    @Test fun typedTextIsSearchableWithItsFrame() {
        val box = TextElement("t", cx = 100f, cy = 50f, width = 80f, height = 20f, text = "قانون\nNewton")
        val empty = TextElement("e", 0f, 0f, 10f, 10f, "   ")
        val out = PageTextExtractor(NoOpHandwritingRecognizer).elements(listOf(box, empty))
        assertEquals(1, out.size)
        assertEquals(SegmentKind.Text, out[0].kind)
        assertEquals("قانون Newton", out[0].text)
        assertEquals(60f, out[0].rect.left, 0.01f)
        assertEquals(60f, out[0].rect.bottom, 0.01f)
    }

    @Test fun turnedBoxesGetTheirEnclosingRect() {
        val box = TextElement("t", cx = 0f, cy = 0f, width = 100f, height = 20f, text = "x", rotation = Math.PI.toFloat() / 2f)
        val r = PageTextExtractor(NoOpHandwritingRecognizer).elements(listOf(box)).single().rect
        assertEquals(20f, r.width, 0.01f)
        assertEquals(100f, r.height, 0.01f)
    }
}
