package app.bestnote.engine.recognition

import app.bestnote.core.model.StylusSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkSegmenterTest {
    private fun stroke(l: Float, t: Float, r: Float, b: Float, time: Long = 0): InkStrokeSamples =
        listOf(StylusSample(l, t, 0.5f, 0f, 0f, time), StylusSample(r, b, 0.5f, 0f, 0f, time + 10))

    @Test fun separatesLinesTopToBottom() {
        val strokes = listOf(stroke(10f, 100f, 60f, 120f), stroke(10f, 20f, 60f, 40f), stroke(70f, 22f, 120f, 42f))
        val lines = InkSegmenter.lines(strokes)
        assertEquals(2, lines.size)
        assertEquals(listOf(1, 2), lines[0].strokes)
        assertEquals(listOf(0), lines[1].strokes)
    }

    @Test fun splitsWordsAtGapsAndKeepsDotsWithTheirWord() {
        val word1 = stroke(10f, 20f, 40f, 40f, 0)
        val gapWord = stroke(70f, 20f, 100f, 40f, 100)
        val dot = stroke(20f, 12f, 22f, 14f, 200) // drawn last, above the first word
        val strokes = listOf(word1, gapWord, dot)
        val words = InkSegmenter.words(strokes)
        assertEquals(2, words.size)
        assertEquals(listOf(0, 2), words[0].strokes)
        assertEquals(listOf(1), words[1].strokes)
    }

    @Test fun touchingStrokesAreOneWord() {
        val strokes = listOf(stroke(10f, 20f, 40f, 40f), stroke(38f, 20f, 70f, 40f), stroke(72f, 20f, 90f, 40f))
        assertEquals(1, InkSegmenter.words(strokes).size)
    }

    @Test fun emptyInkHasNoSegments() = assertTrue(InkSegmenter.words(emptyList()).isEmpty())

    @Test fun corpusLinesAndWords() {
        val english = Corpus.strokes("lecture_en")
        val lines = InkSegmenter.lines(english)
        val words = InkSegmenter.words(english)
        println("lecture_en: ${english.size} strokes, ${lines.size} lines, ${words.size} words")
        lines.forEach { println("L ${it.strokes.size} strokes y=${it.box.top.toInt()}..${it.box.bottom.toInt()} x=${it.box.left.toInt()}..${it.box.right.toInt()}") }
        assertTrue(lines.size in 8..10)
        assertTrue(words.size in 25..60)
        val arabic = Corpus.strokes("arabic")
        println("arabic: ${arabic.size} strokes, ${InkSegmenter.lines(arabic).size} lines, ${InkSegmenter.words(arabic).size} words")
        assertEquals(2, InkSegmenter.lines(arabic).size)
    }
}
