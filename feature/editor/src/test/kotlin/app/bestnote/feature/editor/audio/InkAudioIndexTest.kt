package app.bestnote.feature.editor.audio

import app.bestnote.core.model.AudioSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class InkAudioIndexTest {
    private fun entry(page: Int, rec: String, offset: Long, xs: FloatArray, ys: FloatArray, radius: Float = 1f) =
        InkAudioIndex.Entry(page, AudioSync(rec, offset), xs, ys, radius, Any())

    private val early = entry(0, "r", 1_000, floatArrayOf(0f, 100f), floatArrayOf(0f, 0f))
    private val late = entry(0, "r", 9_000, floatArrayOf(0f, 100f), floatArrayOf(50f, 50f))
    private val otherPage = entry(1, "r", 2_000, floatArrayOf(0f, 100f), floatArrayOf(0f, 0f))
    private val index = InkAudioIndex(listOf(early, late, otherPage))

    @Test fun tapOnAStrokeFindsIt() {
        assertSame(early, index.hit(0, 50f, 2f, slop = 5f))
        assertSame(late, index.hit(0, 30f, 48f, slop = 5f))
        assertSame(otherPage, index.hit(1, 50f, 0f, slop = 5f))
    }

    @Test fun tapAwayFromInkFindsNothing() {
        assertNull(index.hit(0, 50f, 25f, slop = 5f))
    }

    @Test fun nearestStrokeWins() {
        val near = entry(0, "r", 3_000, floatArrayOf(0f, 100f), floatArrayOf(10f, 10f))
        val both = InkAudioIndex(listOf(early, near))
        assertSame(near, both.hit(0, 50f, 8f, slop = 20f))
        assertSame(early, both.hit(0, 50f, 3f, slop = 20f))
    }

    @Test fun singlePointStrokeIsHitAroundItsDot() {
        val dot = entry(0, "r", 500, floatArrayOf(10f), floatArrayOf(10f), radius = 2f)
        assertSame(dot, InkAudioIndex(listOf(dot)).hit(0, 13f, 10f, slop = 2f))
        assertNull(InkAudioIndex(listOf(dot)).hit(0, 30f, 10f, slop = 2f))
    }

    @Test fun inkAfterThePlayheadIsDimmed() {
        assertEquals(listOf(late), index.laterThan("r", 5_000))
        assertEquals(listOf(early, late, otherPage), index.laterThan("r", 0))
        assertEquals(emptyList<InkAudioIndex.Entry>(), index.laterThan("r", 9_000))
        assertEquals(emptyList<InkAudioIndex.Entry>(), index.laterThan("other", 0))
    }

    @Test fun dimSignatureChangesOnlyWhenTheDimmedSetChanges() {
        val a = index.dimSignature("r", 1_500)
        assertEquals(a, index.dimSignature("r", 1_999))
        assertEquals(1, a)
        assertEquals(3, index.dimSignature("r", 9_000))
        assertEquals(0, index.dimSignature("missing", 9_000))
    }
}
