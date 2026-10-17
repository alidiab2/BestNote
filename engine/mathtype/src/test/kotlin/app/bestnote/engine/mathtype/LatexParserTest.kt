package app.bestnote.engine.mathtype

import app.bestnote.engine.mathtype.MathNode.Frac
import app.bestnote.engine.mathtype.MathNode.Row
import app.bestnote.engine.mathtype.MathNode.Scripts
import app.bestnote.engine.mathtype.MathNode.Sym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexParserTest {
    private fun root(s: String) = LatexParser.parse(s).root as Row

    @Test
    fun readsFractionsScriptsAndSymbols() {
        val r = root("\\frac{a}{b} + x^{2}_i \\le \\pi")
        val frac = r.items[0] as Frac
        assertEquals(Sym("a", AtomKind.Ord), frac.num.items.single())
        assertEquals(Sym("+", AtomKind.Bin), r.items[1])
        val x = r.items[2] as Scripts
        assertEquals(Sym("x", AtomKind.Ord), x.base)
        assertEquals("2", (x.sup!!.items.single() as Sym).text)
        assertEquals("i", (x.sub!!.items.single() as Sym).text)
        assertEquals(Sym("≤", AtomKind.Rel), r.items[3])
        assertEquals(Sym("π", AtomKind.Ord), r.items[4])
    }

    @Test
    fun minusIsTheMathMinusAndPrimesAreSuperscripts() {
        val r = root("f''(x)-1")
        val f = r.items[0] as Scripts
        assertEquals("″", (f.sup!!.items.single() as Sym).text)
        assertEquals(Sym("−", AtomKind.Bin), r.items[4])
    }

    @Test
    fun leftRightWithMiddle() {
        val f = root("\\left\\{ x \\middle| x>0 \\right.").items.single() as MathNode.Fenced
        assertEquals("{", f.left)
        assertEquals("", f.right)
        assertEquals(listOf("|"), f.middles)
        assertEquals(2, f.parts.size)
    }

    @Test
    fun environmentsBecomeMatrices() {
        val m = root("\\begin{bmatrix} 1 & 2 \\\\ 3 & 4 \\\\ \\end{bmatrix}").items.single() as MathNode.Matrix
        assertEquals(MathNode.MatrixKind.Matrix, m.kind)
        assertEquals("[", m.left)
        assertEquals(2, m.rows.size)
        assertEquals(2, m.rows[1].size)
    }

    @Test
    fun topLevelLinesAndAlignment() {
        val gathered = LatexParser.parse("a=1 \\\\ b=2").root as MathNode.Matrix
        assertEquals(MathNode.MatrixKind.Gathered, gathered.kind)
        val aligned = LatexParser.parse("a&=1 \\\\ &=2").root as MathNode.Matrix
        assertEquals(MathNode.MatrixKind.Aligned, aligned.kind)
    }

    @Test
    fun operatorsKnowTheirLimits() {
        val r = root("\\sum_{i} \\int_0^1 \\lim_{x} \\sin x \\int\\limits_a^b")
        val sum = (r.items[0] as Scripts).base as MathNode.BigOp
        val int = (r.items[1] as Scripts).base as MathNode.BigOp
        val lim = (r.items[2] as Scripts).base as MathNode.BigOp
        val sin = r.items[3] as MathNode.BigOp
        assertTrue(sum.limitsByDefault && !int.limitsByDefault && lim.limitsByDefault && !sin.limitsByDefault)
        assertTrue(lim.name && sin.name)
        assertEquals(true, ((r.items[5] as Scripts).base as MathNode.BigOp).limits)
    }

    @Test
    fun unicodeSymbolsAreReadToo() {
        val r = root("∑ α ≤ β")
        assertTrue(r.items[0] is MathNode.BigOp)
        assertEquals(AtomKind.Rel, (r.items[2] as Sym).kind)
    }

    @Test
    fun errorsPointAtTheSource() {
        val p = LatexParser.parse("x + \\nope{y} }")
        assertEquals(listOf(LatexError(4, 9, "unknown command \\nope"), LatexError(13, 14, "unexpected }")), p.errors)
        val missing = LatexParser.parse("\\frac{a}{b")
        assertEquals("missing }", missing.errors.single().message)
        assertEquals(LatexError(0, 5, "\\left without \\right"), LatexParser.parse("\\left( x").errors.single())
        assertEquals("double superscript", LatexParser.parse("x^2^3").errors.single().message)
        assertTrue(LatexParser.parse("\\begin{pmatrix} 1").errors.single().message.contains("without \\end"))
    }

    @Test
    fun negationsAndAlphabets() {
        val r = root("\\not= \\mathbb{R} \\mathrm{d}x")
        assertEquals(Sym("≠", AtomKind.Rel), r.items[0])
        assertEquals(Alphabet.DoubleStruck, ((r.items[1] as Row).items.single() as Sym).alphabet)
        assertEquals(Alphabet.Upright, ((r.items[2] as Row).items.single() as Sym).alphabet)
    }
}
