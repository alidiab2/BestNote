package app.bestnote.feature.editor.math

import app.bestnote.ai.math.EquationReader
import app.bestnote.ai.math.EquationReading
import app.bestnote.engine.mathtype.MathTypesetter
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MATH-01: what vision models actually answer for handwritten maths (recorded, with their delimiters, fences and
 * Unicode) goes through the sanitizer and typesets without errors.
 */
class EquationPipelineTest {
    private val tex = MathTypesetter()

    private val recorded = listOf(
        "$$ v = \\frac{\\Delta x}{\\Delta t} $$",
        "\\[ x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a} \\]",
        "```latex\n\\int_0^{\\pi} \\sin x \\, dx = 2\n```",
        "F = ma → a = F/m",
        "x² + y² = r²",
        "\\lim_{n\\to\\infty}\\left(1+\\frac{1}{n}\\right)^n = e",
        "\\begin{aligned} 2x + 3 &= 7 \\\\ 2x &= 4 \\\\ x &= 2 \\end{aligned}",
        "\\begin{pmatrix} 1 & 0 \\\\ 0 & 1 \\end{pmatrix}",
        "f(x) = \\begin{cases} x^2 & x \\ge 0 \\\\ -x & x < 0 \\end{cases}",
        "\\text{السرعة} = \\frac{\\text{المسافة}}{\\text{الزمن}}",
        "\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}",
        "θ = 30°",
        "\\vec{F}_{net} = \\sum \\vec{F}",
        // Live answer (deepseek-flash, 2026-10-17) for tools/handwriting/corpus/formulas.json, via ai-live.sh mode=equation.
        "\\begin{aligned} v &= u + at \\\\ s &= ut + \\frac{1}{2}at^{2} \\\\ \\frac{dy}{dx} &= \\frac{dy}{du} \\cdot \\frac{du}{dx} \\end{aligned}",
    )

    @Test
    fun recordedAnswersSanitizeAndTypesetCleanly() {
        recorded.forEach { raw ->
            val reading = EquationReader.interpret(raw)
            assertTrue("$raw → $reading", reading is EquationReading.Ok)
            val latex = (reading as EquationReading.Ok).latex
            val layout = tex.typeset(latex, 24f)
            assertTrue("$latex: ${layout.errors}", layout.errors.isEmpty())
            assertTrue(latex, layout.width > 0f && layout.height > 0f)
        }
    }

    @Test
    fun unsafeAnswersNeverReachThePage() {
        listOf("\\input{secrets}", "\\def\\x{1} \\x", "\\href{http://x}{y}").forEach {
            assertTrue(it, EquationReader.interpret(it) is EquationReading.Invalid)
        }
    }
}
