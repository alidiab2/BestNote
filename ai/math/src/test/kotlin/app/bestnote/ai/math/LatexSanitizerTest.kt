package app.bestnote.ai.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexSanitizerTest {
    private fun ok(s: String) = (LatexSanitizer.sanitize(s) as LatexResult.Ok).latex

    @Test fun `strips delimiters`() {
        assertEquals("E = mc^2", ok("\$\$E = mc^2\$\$"))
        assertEquals("a+b", ok("\\(a+b\\)"))
    }

    @Test fun `standardises board symbols`() {
        assertEquals("F = m\\cdot a, v^{2} \\le c^{2}", ok("F = m·a, v² ≤ c²"))
        assertEquals("\\forall x \\in A \\Rightarrow x \\notin B", ok("∀x ∈ A ⇒ x ∉ B"))
        assertEquals("\\angle ABC = 90^{\\circ}", ok("∠ABC = 90°"))
    }

    @Test fun `rejects unbalanced and unsafe input`() {
        assertTrue(LatexSanitizer.sanitize("\\frac{a}{b") is LatexResult.Invalid)
        assertTrue(LatexSanitizer.sanitize("\\left( x") is LatexResult.Invalid)
        assertTrue(LatexSanitizer.sanitize("\\input{/etc/passwd}") is LatexResult.Invalid)
        assertTrue(LatexSanitizer.sanitize("\\begin{pmatrix} 1 \\end{bmatrix}") is LatexResult.Invalid)
    }

    @Test fun `escaped braces do not count`() {
        assertEquals("\\{x \\mid x > 0\\}", ok("\\{x \\mid x > 0\\}"))
    }

    @Test
    fun drawnCommandsAreKeptAndBareOnesRepaired() {
        fun ok(raw: String) = (LatexSanitizer.sanitize(raw) as LatexResult.Ok).latex
        assertEquals("\\cancel{W_N} + \\underbrace{a}_{b}", ok("\\cancel{W_N} + \\underbrace{a}_{b}"))
        assertEquals("\\cancel{W_N}", ok("cancel{W_N}"))
        assertEquals("\\boxed{x} \\vec r \\hat x", ok("\\boxed{x} \\vec r \\hat x"))
        assertEquals("\\text{לא משמרים}", ok("\\mathrm{לא משמרים}"))
        assertEquals("\\left\\{ a \\right.", ok("\\left\\{ a \\right."))
    }
}
