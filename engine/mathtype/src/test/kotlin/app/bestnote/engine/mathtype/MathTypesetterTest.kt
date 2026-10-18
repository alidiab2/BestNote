package app.bestnote.engine.mathtype

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MathTypesetterTest {
    private val font = MathFont.stixTwo
    private val tex = MathTypesetter(font)
    private val size = 20f
    private val pt = size / font.unitsPerEm
    private val axis = font.constant("AxisHeight") * pt

    private fun glyphs(l: MathLayout) = l.ops.filterIsInstance<MathOp.GlyphOp>()
    private fun rules(l: MathLayout) = l.ops.filterIsInstance<MathOp.RuleOp>()
    private fun glyphOf(l: MathLayout, cp: Int) = glyphs(l).first { it.glyph.id == font.glyphFor(cp)!!.id }

    /** Top and bottom (y down) of a glyph's ink on the page. */
    private fun MathOp.GlyphOp.top() = y - glyph.yMax * scale
    private fun MathOp.GlyphOp.bottom() = y - glyph.yMin * scale

    @Test
    fun fontLoadsWithMathTables() {
        assertEquals(1000, font.unitsPerEm)
        assertEquals(258, font.constant("AxisHeight"))
        assertTrue(font.glyphCount > 2000)
        assertTrue(font.glyphFor(0x1D465) != null) // math italic x
        assertTrue(font.verticalVariants(font.glyphFor('('.code)!!.id).size > 5)
        assertTrue(font.verticalAssembly(font.glyphFor('('.code)!!.id) != null)
    }

    @Test
    fun lettersAreMathItalicAndDigitsUpright() {
        val l = tex.typeset("x2", size)
        val ids = glyphs(l).map { it.glyph.id }
        assertEquals(listOf(font.glyphFor(0x1D465)!!.id, font.glyphFor('2'.code)!!.id), ids)
        assertTrue(l.errors.isEmpty())
    }

    @Test
    fun fractionStacksNumeratorOverBarOnTheAxisOverDenominator() {
        val l = tex.typeset("\\frac{a}{b}", size)
        val bar = rules(l).single()
        val num = glyphOf(l, 0x1D44E)
        val den = glyphOf(l, 0x1D44F)
        // The bar is centered on the maths axis.
        assertEquals(-axis, bar.top + bar.height / 2, 0.01f)
        assertEquals(font.constant("FractionRuleThickness") * pt, bar.height, 0.01f)
        // Numerator above the bar with at least the display gap; denominator below.
        val gap = font.constant("FractionNumDisplayStyleGapMin") * pt
        assertTrue(num.bottom() <= bar.top - gap + 0.01f)
        assertTrue(den.top() >= bar.top + bar.height + gap - 0.01f)
        // Both are centered over the bar, which spans the wider of the two.
        val numCenter = num.x + num.glyph.advance * num.scale / 2
        assertEquals(bar.x + bar.width / 2, numCenter, 0.5f)
        assertTrue(l.ascent > size * 0.6f && l.descent > size * 0.3f)
    }

    @Test
    fun inlineFractionIsSmallerThanDisplay() {
        val display = tex.typeset("\\frac{a}{b}", size, display = true)
        val inline = tex.typeset("\\frac{a}{b}", size, display = false)
        assertTrue(glyphs(inline).first().scale < glyphs(display).first().scale)
        assertTrue(inline.height < display.height)
    }

    @Test
    fun radicalGrowsWithItsBodyAndCarriesAnOverbar() {
        val small = tex.typeset("\\sqrt{x}", size)
        val tall = tex.typeset("\\sqrt{\\frac{a}{b}}", size)
        val signSmall = glyphs(small).first()
        val signTall = glyphs(tall).first()
        assertNotEquals(signSmall.glyph.id, signTall.glyph.id)
        val bar = rules(small).single()
        // The overbar sits on top of the sign and covers the body.
        assertEquals(signSmall.top(), bar.top, 0.6f)
        val x = glyphOf(small, 0x1D465)
        assertTrue(bar.top + bar.height < x.top())
        assertTrue(bar.x <= x.x && bar.x + bar.width >= x.x + x.glyph.advance * x.scale - 0.01f)
    }

    @Test
    fun nthRootPutsTheIndexSmallAndLeft() {
        val l = tex.typeset("\\sqrt[3]{x}", size)
        val three = glyphOf(l, '3'.code)
        val sign = glyphOf(l, 0x221A)
        assertTrue(three.scale < sign.scale)
        assertTrue(three.x < sign.x + sign.glyph.advance * sign.scale / 2)
        assertTrue(three.bottom() < sign.bottom())
    }

    @Test
    fun superAndSubscriptsAreSmallAndShifted() {
        val l = tex.typeset("x_i^2", size)
        val x = glyphOf(l, 0x1D465)
        val two = glyphOf(l, '2'.code)
        val i = glyphOf(l, 0x1D456)
        assertEquals(font.constant("ScriptPercentScaleDown") / 100f, two.scale / x.scale, 0.001f)
        assertTrue(two.y < x.y - 0.3f * size)
        assertTrue(i.y > x.y)
        assertTrue(two.x >= x.x + x.glyph.advance * x.scale - 0.01f)
        // The gap between them is at least SubSuperscriptGapMin.
        val gap = i.top() - two.bottom()
        assertTrue(gap >= font.constant("SubSuperscriptGapMin") * pt - 0.05f)
    }

    @Test
    fun displaySumHasLimitsAboveAndBelowAndInlineSumBeside() {
        val d = tex.typeset("\\sum_{i=1}^{n} i", size, display = true)
        val sum = glyphs(d).first { it.glyph.id in font.verticalVariants(font.glyphFor(0x2211)!!.id).map { v -> v.glyph } }
        val n = glyphOf(d, 0x1D45B)
        val one = glyphOf(d, '1'.code)
        assertTrue(n.bottom() < sum.top())
        assertTrue(one.top() > sum.bottom())
        // Display size: the larger variant.
        assertNotEquals(font.glyphFor(0x2211)!!.id, sum.glyph.id)

        val t = tex.typeset("\\sum_{i=1}^{n} i", size, display = false)
        val sumT = glyphOf(t, 0x2211)
        val nT = glyphOf(t, 0x1D45B)
        assertTrue(nT.x > sumT.x + sumT.glyph.advance * sumT.scale - 0.01f)
    }

    @Test
    fun bigOperatorIsCenteredOnTheAxis() {
        val l = tex.typeset("\\int f", size, display = true)
        val integral = glyphs(l).first()
        assertEquals(-axis, (integral.top() + integral.bottom()) / 2, 0.2f)
    }

    @Test
    fun delimitersGrowWithWhatTheyHold() {
        val plain = tex.typeset("(x)", size)
        val grown = tex.typeset("\\left( \\frac{\\frac{a}{b}}{\\frac{c}{d}} \\right)", size)
        val p = glyphs(plain).first()
        val g = glyphs(grown).first()
        val plainH = p.bottom() - p.top()
        val grownH = g.bottom() - g.top()
        assertTrue("grown $grownH vs $plainH", grownH > plainH * 1.8f)
        // Centered on the axis.
        assertEquals(-axis, (g.top() + g.bottom()) / 2, 0.5f)
    }

    @Test
    fun veryTallDelimitersAreAssembledFromParts() {
        val rows = (1..12).joinToString(" \\\\ ") { "$it" }
        val l = tex.typeset("\\left( \\begin{matrix} $rows \\end{matrix} \\right)", size)
        val parts = font.verticalAssembly(font.glyphFor('('.code)!!.id)!!.parts.map { it.glyph }.toSet()
        assertTrue(glyphs(l).count { it.glyph.id in parts } >= 3)
        assertTrue(l.errors.isEmpty())
    }

    @Test
    fun matrixLaysOutRowsAndColumns() {
        val l = tex.typeset("\\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix}", size)
        val one = glyphOf(l, '1'.code)
        val two = glyphOf(l, '2'.code)
        val three = glyphOf(l, '3'.code)
        assertEquals(one.y, two.y, 0.01f)
        assertEquals(one.x, three.x, 0.5f)
        assertTrue(three.y > one.y + size * 0.8f)
        assertTrue(two.x > one.x + size * 0.9f)
        // Parentheses on both sides.
        val parens = glyphs(l).filter { it.x < one.x || it.x > two.x }
        assertEquals(2, parens.size)
    }

    @Test
    fun casesAlignLeftWithABraceOnTheLeft() {
        val l = tex.typeset("f(x)=\\begin{cases} 1 & x>0 \\\\ 22 & x\\le 0 \\end{cases}", size)
        assertTrue(l.errors.isEmpty())
        val one = glyphOf(l, '1'.code)
        val twos = glyphs(l).filter { it.glyph.id == font.glyphFor('2'.code)!!.id }
        assertEquals(one.x, twos.minOf { it.x }, 0.01f)
    }

    @Test
    fun alignedLinesLineUpAtTheRelation() {
        val l = tex.typeset("\\begin{aligned} y &= ab \\\\ &= c \\end{aligned}", size)
        val equals = glyphs(l).filter { it.glyph.id == font.glyphFor('='.code)!!.id }
        assertEquals(2, equals.size)
        assertEquals(equals[0].x, equals[1].x, 0.01f)
        assertTrue(equals[1].y > equals[0].y)
    }

    @Test
    fun severalLinesStackCentered() {
        val l = tex.typeset("a=1 \\\\ b=2", size)
        val a = glyphOf(l, 0x1D44E)
        val b = glyphOf(l, 0x1D44F)
        assertTrue(b.y > a.y)
    }

    @Test
    fun relationsAndBinaryOperatorsGetTeXSpacing() {
        val sum = tex.typeset("a+b", size)
        val adjacent = tex.typeset("ab", size)
        val plus = glyphOf(sum, '+'.code)
        val a = glyphOf(sum, 0x1D44E)
        val gap = plus.x - (a.x + a.glyph.advance * a.scale)
        // Medium space (4 mu) plus a's italic correction.
        assertEquals(4f / 18f * size + a.glyph.italic * pt, gap, 0.05f)
        assertTrue(sum.width > adjacent.width)
        // A leading minus is a sign, not an operator: no space after it.
        val neg = tex.typeset("-b", size)
        val minus = glyphOf(neg, 0x2212)
        assertEquals(minus.x + minus.glyph.advance * minus.scale, glyphOf(neg, 0x1D44F).x, 0.01f)
    }

    @Test
    fun accentsSitOverTheirLetter() {
        val l = tex.typeset("\\hat{x} \\vec{v} \\overline{AB} \\widehat{xyz}", size)
        assertTrue(l.errors.isEmpty())
        val x = glyphOf(l, 0x1D465)
        val hat = glyphs(l).first { it.glyph.id == font.glyphFor(0x302)!!.id }
        assertTrue(hat.bottom() < x.top() + 0.5f)
        assertTrue(rules(l).isNotEmpty())
    }

    @Test
    fun greekCalligraphicAndBlackboardLetters() {
        val l = tex.typeset("\\alpha \\Omega \\mathbb{R} \\mathcal{L} \\mathbf{v}", size)
        val ids = glyphs(l).map { it.glyph.id }.toSet()
        listOf(0x1D6FC, 0x3A9, 0x211D, 0x2112, 0x1D42F).forEach { cp -> assertTrue("U+%X".format(cp), font.glyphFor(cp)!!.id in ids) }
    }

    @Test
    fun scalesLinearlyWithSize() {
        val a = tex.typeset("\\int_0^1 x^2\\,dx = \\frac{1}{3}", 10f)
        val b = tex.typeset("\\int_0^1 x^2\\,dx = \\frac{1}{3}", 30f)
        assertEquals(a.width * 3, b.width, 0.05f)
        assertEquals(a.ascent * 3, b.ascent, 0.05f)
    }

    @Test
    fun unknownCommandsAreMarkedNotFatal() {
        val l = tex.typeset("\\foo + x", size)
        assertEquals(LatexError(0, 4, "unknown command \\foo"), l.errors.single())
        // The unknown command is reported but never drawn as text; the rest is drawn.
        assertTrue(l.ops.none { it is MathOp.TextOp })
        assertTrue(l.ops.any { it.tone == Tone.Normal })
    }

    @Test
    fun textUsesTheDeviceFontForArabic() {
        val l = tex.typeset("\\text{السرعة} = \\frac{d}{t}", size)
        val text = l.ops.filterIsInstance<MathOp.TextOp>().single()
        assertEquals("السرعة", text.text)
        assertTrue(l.errors.isEmpty())
    }

    @Test
    fun placeholdersShowEmptySlotsOnlyWhenAsked() {
        assertTrue(tex.typeset("\\frac{}{}", size, placeholders = true).ops.count { it.tone == Tone.Placeholder } == 2)
        assertTrue(tex.typeset("\\frac{}{}", size).ops.none { it.tone == Tone.Placeholder })
    }

    @Test
    fun brokenInputNeverThrows() {
        val samples = listOf(
            "", "{", "}", "\\", "\\frac", "\\frac{", "x^", "_", "^^", "\\left(", "\\right)", "\\begin{pmatrix}", "\\end{cases}",
            "\\sqrt[", "{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{{x", "\\left( \\middle", "&&&\\\\\\\\",
            "\\begin{array}{", "x'''''", "\\not", "\\overbrace{x}^", "\\mathbb{", "a_{b_{c_{d_{e_{f}}}}}", "\\text{",
        )
        val random = Random(7)
        val alphabet = "\\{}[]()^_&'x12+-=frac sqrt left right begin end matrix\\\\ ∑∫αβ"
        val fuzz = List(400) { String(CharArray(random.nextInt(1, 40)) { alphabet[random.nextInt(alphabet.length)] }) }
        for (s in samples + fuzz) {
            val l = tex.typeset(s, size)
            assertFalse(s, l.width.isNaN() || l.ascent.isNaN() || l.descent.isNaN())
            assertTrue(s, l.width >= 0f && l.width < 10_000f)
        }
    }

    @Test
    fun deepNestingIsReportedNotOverflowed() {
        val l = tex.typeset("{".repeat(500) + "x" + "}".repeat(500), size)
        assertTrue(l.hasErrors)
    }

    @Test
    fun representativeStudentFormulasTypesetCleanly() {
        val formulas = listOf(
            "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
            "\\int_{0}^{\\infty} e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}",
            "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1",
            "\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}",
            "\\prod_{p \\text{ prime}} \\frac{1}{1-p^{-s}}",
            "\\det \\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix} = ad - bc",
            "\\left\\{ x \\in \\mathbb{R} \\middle| x^2 < 2 \\right\\}",
            "f'(x) = \\lim_{h \\to 0} \\frac{f(x+h) - f(x)}{h}",
            "\\vec{F} = m\\vec{a}",
            "\\overbrace{1+2+\\cdots+n}^{n \\text{ terms}}",
            "\\binom{n}{k} = \\frac{n!}{k!(n-k)!}",
            "a \\equiv b \\pmod{n}",
            "\\boxed{E = mc^2}",
            "\\Delta x \\geq \\frac{\\hbar}{2 \\Delta p}",
            "\\oint_C \\vec{E} \\cdot d\\vec{\\ell} = -\\frac{d\\Phi_B}{dt}",
        )
        for (f in formulas) {
            val l = tex.typeset(f, size)
            assertTrue("$f: ${l.errors}", l.errors.isEmpty())
            assertTrue(f, l.ops.none { it is MathOp.TextOp })
            assertTrue(f, l.width > size)
        }
    }
}

class MathCoverageTest {
    private val tex = MathTypesetter(MathFont.stixTwo)
    private fun lines(l: MathLayout) = l.ops.filterIsInstance<MathOp.LineOp>()

    @Test
    fun cancelDrawsOneDiagonalStrike() {
        val l = tex.typeset("\\cancel{W_N}", 20f)
        assertTrue(l.errors.isEmpty())
        val s = lines(l).single()
        assertTrue(s.x2 > s.x1 && s.y2 < s.y1) // bottom-left to top-right
        assertTrue(l.ops.none { it is MathOp.TextOp })
    }

    @Test
    fun bcancelAndXcancel() {
        val b = lines(tex.typeset("\\bcancel{x}", 20f)).single()
        assertTrue(b.y2 > b.y1)
        assertEquals(2, lines(tex.typeset("\\xcancel{x}", 20f)).size)
    }

    @Test
    fun cancelToKeepsTheTarget() {
        val plain = tex.typeset("\\cancel{x}", 20f)
        val to = tex.typeset("\\cancelto{0}{x}", 20f)
        assertEquals(1, lines(to).size)
        assertTrue(to.width > plain.width)
    }

    @Test
    fun bracesWithLabelsAreTallerThanTheBody() {
        val body = tex.typeset("W", 20f)
        assertTrue(tex.typeset("\\underbrace{W}_{\\text{x}}", 20f).descent > body.descent)
        assertTrue(tex.typeset("\\overbrace{W}^{n}", 20f).ascent > body.ascent)
    }

    @Test
    fun vectorsAccentsBoxAndLines() {
        for (s in listOf("d\\vec r", "\\hat x", "\\overrightarrow{AB}", "\\boxed{E=mc^2}", "\\underline{x}", "\\overline{x}", "\\left\\{ \\begin{matrix} a \\\\ b \\end{matrix} \\right\\} = 0", "a\\quad b\\, c")) {
            val l = tex.typeset(s, 20f)
            assertTrue("$s: ${l.errors}", l.errors.isEmpty())
            assertTrue(l.ops.isNotEmpty())
        }
    }

    @Test
    fun hebrewTextIsOneRtlRun() {
        val l = tex.typeset("W_{\\text{לא משמרים}}", 20f)
        val run = l.ops.filterIsInstance<MathOp.TextOp>().single { it.rtl }
        assertEquals("לא משמרים", run.text)
    }

    @Test
    fun unknownCommandNeverPrintsItsName() {
        val l = tex.typeset("a \\frobnicate{x+1} b", 20f)
        assertTrue(l.errors.isNotEmpty())
        assertTrue(l.ops.none { it is MathOp.TextOp && it.text.contains("frobnicate") })
        val bare = tex.typeset("a \\zzz b", 20f)
        assertTrue(bare.ops.none { it is MathOp.TextOp && it.text.contains("zzz") })
    }
}
