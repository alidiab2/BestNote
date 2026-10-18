package app.bestnote.engine.handwriting

import app.bestnote.engine.handwriting.MathParser.Frac
import app.bestnote.engine.handwriting.MathParser.Scripts
import app.bestnote.engine.handwriting.MathParser.Sqrt
import app.bestnote.engine.handwriting.MathParser.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MathParserTest {
    @Test fun `fractions roots and scripts`() {
        val row = MathParser.parse("a = \\frac{F}{m} + \\sqrt{x^2_1}").single()
        assertTrue(row.any { it is Frac })
        val root = row.filterIsInstance<Sqrt>().single()
        val s = root.body.single() as Scripts
        assertEquals(Text("x"), s.base)
        assertEquals(listOf(Text("2")), s.sup)
        assertEquals(listOf(Text("1")), s.sub)
    }

    @Test fun `commands become the symbols a student writes`() {
        val row = MathParser.parse("\\theta \\cdot \\pi \\le \\infty \\Rightarrow \\sin x").single()
        val text = row.filterIsInstance<Text>().joinToString("") { it.s }
        assertEquals("θ·π≤∞⇒sinx", text)
    }

    @Test fun `text keeps its words and rows split on line breaks`() {
        val rows = MathParser.parse("v = 3\\,\\text{m per s} \\\\ t = 2")
        assertEquals(2, rows.size)
        assertTrue(rows[0].contains(Text("per")))
    }

    @Test fun `unknown and malformed input never throws`() {
        listOf("\\foo{", "}}", "^", "\\frac{1}", "\\sqrt", "\\").forEach { MathParser.parse(it) }
    }
}

class MathDecoParserTest {
    private fun first(tex: String) = MathParser.parse(tex).single().first()

    @Test
    fun cancelFamilyParses() {
        val d = first("\\cancel{W_N}") as MathParser.Deco
        assertEquals(MathParser.DecoKind.Cancel, d.kind)
        assertEquals(MathParser.DecoKind.BCancel, (first("\\bcancel x") as MathParser.Deco).kind)
        assertEquals(MathParser.DecoKind.XCancel, (first("\\xcancel{x}") as MathParser.Deco).kind)
        val to = first("\\cancelto{0}{x}") as MathParser.Deco
        assertEquals(MathParser.DecoKind.CancelTo, to.kind)
        assertNotNull(to.label)
    }

    @Test
    fun underbraceTakesItsLabel() {
        val d = first("\\underbrace{a+b}_{\\text{sum}}") as MathParser.Deco
        assertEquals(MathParser.DecoKind.UnderBrace, d.kind)
        assertNotNull(d.label)
        assertEquals(MathParser.DecoKind.Vec, (first("\\vec r") as MathParser.Deco).kind)
        assertEquals(MathParser.DecoKind.Hat, (first("\\hat{x}") as MathParser.Deco).kind)
    }

    @Test
    fun unknownCommandIsNeverWrittenAsText() {
        fun texts(n: List<MathParser.Node>): List<String> = n.flatMap {
            when (it) {
                is MathParser.Text -> listOf(it.s)
                is MathParser.Group -> texts(it.body)
                else -> emptyList()
            }
        }
        val rows = MathParser.parse("a \\frobnicate{b} c \\zzz")
        assertTrue(texts(rows.single()).none { it.contains("frobnicate") || it.contains("zzz") })
        assertTrue(texts(rows.single()).contains("b"))
    }

    @Test
    fun hebrewTextStaysOneRun() {
        val g = MathParser.parse("\\text{לא משמרים}").single().single() as MathParser.Group
        assertEquals(3, g.body.size)
    }
}
