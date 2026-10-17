package app.bestnote.ai.math

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class EquationReaderTest {
    /** A recorded answer instead of a live model. */
    private class Recorded(private val answer: String) : EquationRecognizer {
        var calls = 0
        override suspend fun isAvailable() = true
        override suspend fun recognize(png: ByteArray): String = answer.also { calls++ }
    }

    @Test
    fun cleansDelimitersFencesAndUnicode() {
        assertEquals(EquationReading.Ok("x^{2} \\le y", "\$\$x² ≤ y\$\$"), EquationReader.interpret("\$\$x² ≤ y\$\$"))
        assertEquals("\\frac{a}{b}", (EquationReader.interpret("```latex\n\\frac{a}{b}\n```") as EquationReading.Ok).latex)
        assertEquals("E=mc^2", (EquationReader.interpret("\\begin{equation*}E=mc^2\\end{equation*}") as EquationReading.Ok).latex)
    }

    @Test
    fun emptyAnswersMeanNoMaths() {
        assertEquals(EquationReading.NotMath, EquationReader.interpret("  "))
        assertEquals(EquationReading.NotMath, EquationReader.interpret("$$ $$"))
        assertEquals(EquationReading.NotMath, EquationReader.interpret("NONE"))
    }

    @Test
    fun unsafeOrBrokenAnswersAreRefused() {
        assertEquals("forbidden command \\input", (EquationReader.interpret("\\input{/etc/passwd}") as EquationReading.Invalid).reason)
        assertEquals("unbalanced '{'", (EquationReader.interpret("\\frac{a}{b") as EquationReading.Invalid).reason)
    }

    @Test
    fun readsThroughTheRecognizer() = runBlocking {
        val r = Recorded("\\[ \\int_0^1 x\\,dx = \\tfrac12 \\]")
        assertEquals(EquationReading.Ok("\\int_0^1 x\\,dx = \\tfrac12", "\\[ \\int_0^1 x\\,dx = \\tfrac12 \\]"), EquationReader.read(r, ByteArray(1)))
        assertEquals(1, r.calls)
    }
}
