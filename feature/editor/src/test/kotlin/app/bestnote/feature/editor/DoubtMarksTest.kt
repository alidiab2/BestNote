package app.bestnote.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubtMarksTest {
    private val text = "the frietion force"
    private val marks = listOf(4..11, 13..17) // "frietion", "force"

    @Test fun fixingAMarkedWordRemovesOnlyItsMark() {
        assertEquals(listOf(13..17), DoubtMarks.shift(text, "the friction force", marks))
    }

    @Test fun editsBeforeShiftTheMarksAndEditsAfterKeepThem() {
        assertEquals(listOf(8..15, 17..21), DoubtMarks.shift(text, "Now the frietion force", marks))
        assertEquals(marks, DoubtMarks.shift(text, "$text\nmore", marks))
        assertEquals(marks, DoubtMarks.shift(text, text, marks))
    }
}
