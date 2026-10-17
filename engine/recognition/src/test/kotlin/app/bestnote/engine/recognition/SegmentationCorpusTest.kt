package app.bestnote.engine.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines, directions and writing order on the whole handwriting corpus (REC-10), against the text that was written. */
class SegmentationCorpusTest {
    private val arabic = setOf("arabic", "arabic_para", "arabic_moved", "mixed")

    @Test fun everyCorpusFileSplitsIntoTheLinesThatWereWritten() {
        for (name in Corpus.transcribed) {
            val strokes = Corpus.strokes(name)
            val lines = InkSegmenter.lines(strokes)
            println("$name: ${lines.size} lines, ${lines.map { InkSegmenter.words(strokes, it).size }} words")
            assertEquals(name, Corpus.expect(name)!!.size, lines.size)
            // Every stroke is in exactly one line.
            assertEquals(name, strokes.indices.toList(), lines.flatMap { it.strokes }.sorted())
        }
    }

    @Test fun linesReadInTheirScriptsDirection() {
        for (name in Corpus.transcribed) {
            val strokes = Corpus.strokes(name)
            val expected = if (name in arabic) WritingDirection.Rtl else WritingDirection.Ltr
            val expect = Corpus.expect(name)!!
            InkSegmenter.lines(strokes).forEachIndexed { i, line ->
                val dir = ReadingChooser.direction(line.strokes.map { strokes[it] })
                // A line of one or two words may not show a trend; it must never point the wrong way.
                if (expect[i].split(' ').size > 2) assertEquals("$name line $i", expected, dir) else assertTrue("$name line $i", dir != expected.opposite())
            }
        }
    }

    @Test fun writingOrderPutsMovedWordsBackInPlace() {
        // The same writing, once in drawing order and once after every 4th (3rd) word was moved to the end of the stroke list.
        for ((pair, rtl) in listOf(("para_tight" to "para_moved") to false, ("arabic_para" to "arabic_moved") to true)) {
            val (clean, moved) = pair
            val cleanJumps = jumps(clean, rtl, reorder = false)
            val cleanReordered = jumps(clean, rtl, reorder = true)
            val movedJumps = jumps(moved, rtl, reorder = false)
            val movedReordered = jumps(moved, rtl, reorder = true)
            println("back jumps: $clean $cleanJumps -> $cleanReordered reordered; $moved $movedJumps -> $movedReordered reordered")
            assertTrue(moved, movedJumps > cleanJumps)
            assertTrue(moved, movedReordered <= cleanJumps) // as tidy as ink written in order (dots/bars after a word count)
            assertTrue(clean, cleanReordered <= cleanJumps) // ink in order is not made worse
        }
    }

    @Test fun inkWrittenInOrderKeepsItsOrder() {
        for (name in Corpus.transcribed.filter { !it.endsWith("moved") }) {
            val strokes = Corpus.strokes(name)
            for (line in InkSegmenter.lines(strokes)) for (rtl in listOf(false, true)) {
                assertEquals("$name rtl=$rtl", line.strokes, InkSegmenter.writingOrder(strokes, line.strokes, rtl))
            }
        }
    }

    private fun jumps(name: String, rtl: Boolean, reorder: Boolean): Int {
        val strokes = Corpus.strokes(name)
        return InkSegmenter.lines(strokes).sumOf { line ->
            backJumps(strokes, if (reorder) InkSegmenter.writingOrder(strokes, line.strokes, rtl) else line.strokes, rtl)
        }
    }

    /**
     * How often a stroke lies far behind (against the writing direction) the ink read so far on its line: more than
     * a few letter heights, so a dot or t-bar added to the word just written does not count.
     */
    private fun backJumps(strokes: List<InkStrokeSamples>, order: List<Int>, rtl: Boolean): Int {
        val heights = order.map { i -> strokes[i].maxOf { it.y } - strokes[i].minOf { it.y } }.sorted()
        val slack = 2f * heights[(heights.size - 1) * 3 / 4]
        var front = if (rtl) Float.MAX_VALUE else -Float.MAX_VALUE
        var jumps = 0
        for (i in order) {
            val l = strokes[i].minOf { it.x }
            val r = strokes[i].maxOf { it.x }
            if (if (rtl) l > front + slack else r < front - slack) jumps++
            front = if (rtl) minOf(front, l) else maxOf(front, r)
        }
        return jumps
    }

    private fun WritingDirection.opposite() = when (this) {
        WritingDirection.Ltr -> WritingDirection.Rtl
        WritingDirection.Rtl -> WritingDirection.Ltr
        WritingDirection.Unknown -> WritingDirection.Unknown
    }
}
