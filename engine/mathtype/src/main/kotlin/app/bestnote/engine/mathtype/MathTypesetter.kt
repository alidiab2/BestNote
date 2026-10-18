package app.bestnote.engine.mathtype

import app.bestnote.engine.mathtype.MathNode.MatrixKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Typesets LaTeX the way TeX does, driven by the OpenType MATH table of [MathFont] (STIX Two Math):
 * TeX's four styles and atom spacing, stacked fractions on the maths axis, radicals that grow with
 * their contents, size variants and glyph assemblies for delimiters, display-size operators with
 * limits, accents placed at their attachment points, matrices and aligned lines.
 *
 * The result is a list of vector drawing operations ([MathLayout]); it never throws (a broken
 * formula is drawn as far as it can be, with its errors marked).
 */
class MathTypesetter(
    private val font: MathFont = MathFont.stixTwo,
    private val measurer: TextMeasurer = TextMeasurer.Approximate,
) {
    /**
     * [latex] at [sizePt] points per em. [display] typesets it as a displayed formula (large operators,
     * limits above and below); [placeholders] draws empty slots (`\frac{}{}`) as faint boxes for the editor.
     */
    fun typeset(latex: String, sizePt: Float, display: Boolean = true, placeholders: Boolean = false): MathLayout {
        val parsed = LatexParser.parse(latex)
        return try {
            val box = Job(sizePt, placeholders).node(parsed.root, Style(if (display) 0 else 1, false))
            flatten(box, parsed.errors)
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            fallback(latex, sizePt, parsed.errors + LatexError(0, latex.length, "cannot typeset: ${e.javaClass.simpleName}"))
        } catch (@Suppress("SwallowedException") e: StackOverflowError) {
            fallback(latex, sizePt, parsed.errors + LatexError(0, latex.length, "formula nested too deeply"))
        }
    }

    private fun fallback(latex: String, sizePt: Float, errors: List<LatexError>): MathLayout {
        val m = measurer.measure(latex, sizePt, false, false)
        return MathLayout(m[0], m[1], m[2], listOf(MathOp.TextOp(latex, 0f, 0f, sizePt, false, false, Tone.Error)), errors)
    }

    // ---- styles -------------------------------------------------------------------------------------------------

    /** TeX style: 0 display, 1 text, 2 script, 3 scriptscript; [cramped] lowers superscripts. */
    private data class Style(val level: Int, val cramped: Boolean) {
        val display get() = level == 0
        fun sup() = Style(if (level < 2) 2 else 3, cramped)
        fun sub() = Style(if (level < 2) 2 else 3, true)
        fun num() = Style(if (level == 0) 1 else if (level == 1) 2 else 3, cramped)
        fun den() = Style(if (level == 0) 1 else if (level == 1) 2 else 3, true)
        fun cramp() = Style(level, true)
    }

    // ---- boxes --------------------------------------------------------------------------------------------------

    /** A laid-out piece: [width], [height] above and [depth] below its baseline (either may be negative). */
    private sealed class Box(val width: Float, val height: Float, val depth: Float)

    private class GlyphBox(val glyph: Glyph, val scale: Float) :
        Box(glyph.advance * scale, glyph.yMax * scale, -glyph.yMin * scale) {
        val italic get() = glyph.italic * scale
    }

    private class RuleBox(width: Float, height: Float) : Box(width, height, 0f)

    private class TextBox(val text: String, val size: Float, val bold: Boolean, val italic: Boolean, w: Float, h: Float, d: Float, val rtl: Boolean = false) : Box(w, h, d)

    /** A line from the box origin to (dx, dy); it takes no room (the caller sizes the group). */
    private class LineBox(val dx: Float, val dy: Float, val thickness: Float) : Box(0f, 0f, 0f)

    private class Placed(val x: Float, val y: Float, val box: Box)

    private class HBox(val items: List<Placed>, width: Float, height: Float, depth: Float, val tone: Tone? = null) : Box(width, height, depth)

    private fun kern(w: Float) = HBox(emptyList(), w, 0f, 0f)

    private val empty get() = kern(0f)

    /** Boxes side by side on one baseline. */
    private fun hbox(boxes: List<Box>): HBox {
        var x = 0f
        var h = Float.NEGATIVE_INFINITY
        var d = Float.NEGATIVE_INFINITY
        val items = ArrayList<Placed>(boxes.size)
        for (b in boxes) {
            items += Placed(x, 0f, b)
            x += b.width
            if (b.width != 0f || b.height != 0f || b.depth != 0f || b !is HBox) {
                h = max(h, b.height)
                d = max(d, b.depth)
            }
        }
        return HBox(items, x, if (h.isInfinite()) 0f else h, if (d.isInfinite()) 0f else d)
    }

    /** [placed] boxes, measured: the bounding box of their ink extents, with the given [width]. */
    private fun group(placed: List<Placed>, width: Float, tone: Tone? = null): HBox {
        var h = 0f
        var d = 0f
        for (p in placed) {
            h = max(h, p.box.height - p.y)
            d = max(d, p.box.depth + p.y)
        }
        return HBox(placed, width, h, d, tone)
    }

    private fun toned(b: Box, tone: Tone) = HBox(listOf(Placed(0f, 0f, b)), b.width, b.height, b.depth, tone)

    // ---- the typesetting job -----------------------------------------------------------------------------------

    private inner class Job(val size: Float, val placeholders: Boolean) {
        val upem = font.unitsPerEm.toFloat()

        fun pct(s: Style) = when (s.level) {
            0, 1 -> 1f
            2 -> font.constant("ScriptPercentScaleDown") / PERCENT
            else -> font.constant("ScriptScriptPercentScaleDown") / PERCENT
        }

        /** Points per font unit in style [s]. */
        fun scale(s: Style) = size / upem * pct(s)

        fun em(s: Style) = size * pct(s)

        fun c(name: String, s: Style) = font.constant(name) * scale(s)

        fun axis(s: Style) = c("AxisHeight", s)

        /** One node as a box in style [s]. */
        fun node(n: MathNode, s: Style): Box = atom(n, s).box

        inner class Atom(var kind: AtomKind, val box: Box, val transparent: Boolean = false)

        /** A list of atoms with TeX's spacing between them. [slot] marks a required argument (shows a placeholder when empty). */
        fun row(items: List<MathNode>, s: Style, slot: Boolean = false): Box {
            if (items.isEmpty()) return if (slot && placeholders) placeholder(s) else empty
            val atoms = items.map { atom(it, s) }
            // Binary operators with nothing to join become ordinary ("-x", "(+").
            var prev: Atom? = null
            for (a in atoms) {
                if (a.transparent) continue
                if (a.kind == AtomKind.Bin && (prev == null || prev.kind in BIN_BREAKERS)) a.kind = AtomKind.Ord
                if (prev != null && prev.kind == AtomKind.Bin && a.kind in BIN_ENDERS) prev.kind = AtomKind.Ord
                prev = a
            }
            if (prev?.kind == AtomKind.Bin) prev.kind = AtomKind.Ord
            val boxes = ArrayList<Box>(atoms.size * 2)
            var last: Atom? = null
            for (a in atoms) {
                if (!a.transparent) {
                    last?.let { l -> spacing(l.kind, a.kind, s).takeIf { it != 0f }?.let { boxes += kern(it) } }
                    last = a
                }
                boxes += a.box
            }
            return hbox(boxes)
        }

        fun row(r: MathNode.Row, s: Style, slot: Boolean = false) = row(r.items, s, slot)

        fun spacing(left: AtomKind, right: AtomKind, s: Style): Float {
            val code = SPACING[left.ordinal][right.ordinal]
            if (code == 0 || (code < 0 && s.level >= 2)) return 0f
            return MU[abs(code)] * em(s) / MU_PER_EM
        }

        fun placeholder(s: Style): Box {
            val g = font.glyphFor(PLACEHOLDER) ?: return kern(em(s) / 2)
            return toned(GlyphBox(g, scale(s)), Tone.Placeholder)
        }

        @Suppress("CyclomaticComplexMethod")
        fun atom(n: MathNode, s: Style): Atom = when (n) {
            is MathNode.Sym -> Atom(n.kind, sym(n, s, withItalic = true).first)
            is MathNode.Row -> Atom(n.kind, row(n, s))
            is MathNode.Text -> Atom(AtomKind.Ord, text(n.text, s, n.bold, n.italic))
            is MathNode.Frac -> Atom(AtomKind.Inner, frac(n, s))
            is MathNode.Radical -> Atom(AtomKind.Ord, radical(n, s))
            is MathNode.Scripts -> scripts(n, s)
            is MathNode.BigOp -> Atom(AtomKind.Op, scripts(MathNode.Scripts(n, null, null), s).box)
            is MathNode.Fenced -> Atom(AtomKind.Inner, fenced(n, s))
            is MathNode.BigDelim -> Atom(n.kind, delimiter(n.delim, em(s) * BIG_SIZES[(n.size - 1).coerceIn(0, 3)], s))
            is MathNode.Accent -> Atom(AtomKind.Ord, accent(n, s))
            is MathNode.Line -> Atom(AtomKind.Ord, line(n, s))
            is MathNode.Brace -> Atom(AtomKind.Ord, brace(n, s))
            is MathNode.Stack -> Atom(n.kind ?: kindOf(n.base), stack(n, s))
            is MathNode.Matrix -> Atom(if (n.left.isNotEmpty() || n.right.isNotEmpty()) AtomKind.Inner else AtomKind.Ord, matrix(n, s))
            is MathNode.Space -> Atom(AtomKind.Ord, kern(n.mu * em(s) / MU_PER_EM), transparent = true)
            is MathNode.Styled -> Atom(AtomKind.Ord, row(n.body, Style(n.level, s.cramped && n.level >= s.level)))
            is MathNode.Boxed -> Atom(AtomKind.Ord, boxed(n, s))
            is MathNode.Cancel -> Atom(AtomKind.Ord, cancel(n, s))
            is MathNode.Phantom -> {
                val b = row(n.body, s)
                Atom(AtomKind.Ord, HBox(emptyList(), if (n.width) b.width else 0f, if (n.height) b.height else 0f, if (n.height) b.depth else 0f))
            }
            is MathNode.Error -> Atom(AtomKind.Ord, toned(text(n.source, s, bold = false, italic = false), Tone.Error))
        }

        fun kindOf(r: MathNode.Row): AtomKind = (r.items.singleOrNull() as? MathNode.Sym)?.kind ?: r.kind

        /** A symbol's glyphs; second = its italic correction (added to the width when [withItalic]). */
        fun sym(n: MathNode.Sym, s: Style, withItalic: Boolean): Pair<Box, Float> {
            val cps = n.text.codePoints().toArray()
            val glyphs = cps.map { font.glyphFor(MathSymbols.styled(it, n.alphabet)) ?: font.glyphFor(it) }
            if (glyphs.any { it == null }) return text(n.text, s, bold = false, italic = false) to 0f
            val sc = scale(s)
            val boxes = glyphs.map { GlyphBox(it!!, sc) }
            val italic = if (n.alphabet in ITALIC_ALPHABETS) boxes.last().italic else 0f
            val core: Box = boxes.singleOrNull() ?: hbox(boxes)
            return if (withItalic && italic > 0f) hbox(listOf(core, kern(italic))) to italic else core to italic
        }

        /** Upright words: math-font glyphs where it has them, the device font for the rest. */
        fun text(t: String, s: Style, bold: Boolean, italic: Boolean): Box {
            if (t.none { isRtl(it.code) }) return textLtr(t, s, bold, italic)
            // Right-to-left words (Hebrew, Arabic) become one run each, shaped and ordered by the device's BiDi
            // engine; neutrals (spaces, digits, punctuation) between two RTL letters belong to the run.
            val first = t.indexOfFirst { isRtl(it.code) }
            val last = t.indexOfLast { isRtl(it.code) }
            val parts = ArrayList<Box>()
            if (first > 0) parts += textLtr(t.substring(0, first), s, bold, italic)
            val run = t.substring(first, last + 1)
            val m = measurer.measure(run, em(s), bold, italic)
            parts += TextBox(run, em(s), bold, italic, m[0], m[1], m[2], rtl = true)
            if (last + 1 < t.length) parts += text(t.substring(last + 1), s, bold, italic)
            return parts.singleOrNull() ?: hbox(parts)
        }

        fun isRtl(cp: Int) = cp in RTL_START..RTL_END || cp in 0xFB1D..0xFDFF || cp in 0xFE70..0xFEFF

        fun textLtr(t: String, s: Style, bold: Boolean, italic: Boolean): Box {
            val sc = scale(s)
            val boxes = ArrayList<Box>()
            val pending = StringBuilder()
            fun flush() {
                if (pending.isEmpty()) return
                val m = measurer.measure(pending.toString(), em(s), bold, italic)
                boxes += TextBox(pending.toString(), em(s), bold, italic, m[0], m[1], m[2])
                pending.setLength(0)
            }
            var k = 0
            while (k < t.length) {
                val cp = t.codePointAt(k)
                k += Character.charCount(cp)
                val alphabet = when {
                    bold && italic -> Alphabet.BoldItalic
                    bold -> Alphabet.Bold
                    italic -> Alphabet.Italic
                    else -> Alphabet.Upright
                }
                val g = font.glyphFor(MathSymbols.styled(cp, alphabet)).takeIf { cp < RTL_START || cp > RTL_END }
                if (g == null) {
                    pending.appendCodePoint(cp)
                } else if (pending.isNotEmpty() && cp == ' '.code) {
                    // Spaces between device-font words stay with them, so right-to-left words keep their order.
                    pending.appendCodePoint(cp)
                } else {
                    flush()
                    boxes += GlyphBox(g, sc)
                }
            }
            flush()
            return boxes.singleOrNull() ?: hbox(boxes)
        }

        // ---- fractions -----------------------------------------------------------------------------------------

        fun frac(n: MathNode.Frac, outer: Style): Box {
            val s = when (n.style) {
                MathNode.FracStyle.Display -> Style(0, outer.cramped)
                MathNode.FracStyle.Text -> Style(if (outer.level == 0) 1 else outer.level, outer.cramped)
                MathNode.FracStyle.Auto -> outer
            }
            val num = row(n.num, s.num(), slot = true)
            val den = row(n.den, s.den(), slot = true)
            val d = s.display
            val axis = axis(s)
            var u: Float
            var v: Float
            val t: Float
            if (n.rule) {
                t = c("FractionRuleThickness", s)
                u = c(if (d) "FractionNumeratorDisplayStyleShiftUp" else "FractionNumeratorShiftUp", s)
                v = c(if (d) "FractionDenominatorDisplayStyleShiftDown" else "FractionDenominatorShiftDown", s)
                val gapNum = c(if (d) "FractionNumDisplayStyleGapMin" else "FractionNumeratorGapMin", s)
                val gapDen = c(if (d) "FractionDenomDisplayStyleGapMin" else "FractionDenominatorGapMin", s)
                u = max(u, gapNum + axis + t / 2 + num.depth)
                v = max(v, gapDen - axis + t / 2 + den.height)
            } else {
                t = 0f
                u = c(if (d) "StackTopDisplayStyleShiftUp" else "StackTopShiftUp", s)
                v = c(if (d) "StackBottomDisplayStyleShiftDown" else "StackBottomShiftDown", s)
                val gap = c(if (d) "StackDisplayStyleGapMin" else "StackGapMin", s)
                val actual = (u - num.depth) - (den.height - v)
                if (actual < gap) {
                    u += (gap - actual) / 2
                    v += (gap - actual) / 2
                }
            }
            val pad = em(s) * NULL_DELIMITER
            val w = max(num.width, den.width)
            val placed = ArrayList<Placed>(3)
            placed += Placed(pad + (w - num.width) / 2, -u, num)
            placed += Placed(pad + (w - den.width) / 2, v, den)
            if (n.rule) placed += Placed(pad, -(axis - t / 2), RuleBox(w, t))
            val body = group(placed, w + 2 * pad)
            if (n.left.isEmpty() && n.right.isEmpty()) return body
            return fence(n.left, listOf(body), emptyList(), n.right, s)
        }

        // ---- radicals ------------------------------------------------------------------------------------------

        fun radical(n: MathNode.Radical, s: Style): Box {
            val body = row(n.body, s.cramp(), slot = true)
            val t = c("RadicalRuleThickness", s)
            var gap = c(if (s.display) "RadicalDisplayStyleVerticalGap" else "RadicalVerticalGap", s)
            val extra = c("RadicalExtraAscender", s)
            val target = body.height + body.depth + gap + t
            val sign = stretch(font.glyphFor(RADICAL) ?: return body, target, s, vertical = true)
            val total = sign.height + sign.depth
            if (total > target) gap += (total - target) / 2
            // The sign's top meets the overbar: shift it so its top is at body.height + gap + t.
            val signY = sign.height - (body.height + gap + t)
            val placed = ArrayList<Placed>()
            var x = 0f
            n.index?.let { idx ->
                val index = row(idx, Style(3, s.cramped))
                val before = c("RadicalKernBeforeDegree", s)
                val after = c("RadicalKernAfterDegree", s)
                val raise = font.constant("RadicalDegreeBottomRaisePercent") / PERCENT * total
                val signBottom = sign.depth + signY
                placed += Placed(x + before, signBottom - raise - index.depth, index)
                x += max(0f, before + index.width + after)
            }
            placed += Placed(x, signY, sign)
            x += sign.width
            val top = body.height + gap + t
            placed += Placed(x, -(top - t), RuleBox(body.width, t))
            placed += Placed(x, 0f, body)
            val g = group(placed, x + body.width)
            return HBox(g.items, g.width, max(g.height, top + extra), g.depth)
        }

        // ---- scripts and operators -------------------------------------------------------------------------------

        @Suppress("CyclomaticComplexMethod")
        fun scripts(n: MathNode.Scripts, s: Style): Atom {
            val base = n.base
            if (base is MathNode.BigOp) {
                val (op, italic) = bigOp(base, s)
                val limits = base.limits ?: (base.limitsByDefault && s.display)
                if (n.sup == null && n.sub == null) return Atom(AtomKind.Op, op)
                val box = if (limits) limits(op, italic, n.sup, n.sub, s) else attach(op, italic, isChar = false, isOp = true, sup = n.sup, sub = n.sub, s = s)
                return Atom(AtomKind.Op, box)
            }
            val kind: AtomKind
            val box: Box
            var italic = 0f
            var isChar = false
            when (base) {
                null -> { kind = AtomKind.Ord; box = empty }
                is MathNode.Sym -> {
                    val (b, it) = sym(base, s, withItalic = false)
                    kind = base.kind
                    box = b
                    italic = it
                    isChar = base.text.codePointCount(0, base.text.length) == 1
                }
                else -> {
                    val a = atom(base, s)
                    kind = a.kind
                    box = a.box
                }
            }
            return Atom(kind, attach(box, italic, isChar, isOp = false, sup = n.sup, sub = n.sub, s = s))
        }

        /** Scripts beside [base] (OpenType MATH superscript/subscript rules). */
        fun attach(base: Box, italic: Float, isChar: Boolean, isOp: Boolean, sup: MathNode.Row?, sub: MathNode.Row?, s: Style): Box {
            val supBox = sup?.let { row(it, s.sup(), slot = true) }
            val subBox = sub?.let { row(it, s.sub(), slot = true) }
            var u = 0f
            var v = 0f
            if (supBox != null) {
                u = max(c(if (s.cramped) "SuperscriptShiftUpCramped" else "SuperscriptShiftUp", s), supBox.depth + c("SuperscriptBottomMin", s))
                if (!isChar) u = max(u, base.height - c("SuperscriptBaselineDropMax", s))
            }
            if (subBox != null) {
                v = max(c("SubscriptShiftDown", s), subBox.height - c("SubscriptTopMax", s))
                if (!isChar) v = max(v, base.depth + c("SubscriptBaselineDropMin", s))
            }
            if (supBox != null && subBox != null) {
                val gap = (u - supBox.depth) - (subBox.height - v)
                val minGap = c("SubSuperscriptGapMin", s)
                if (gap < minGap) {
                    v += minGap - gap
                    val delta = c("SuperscriptBottomMaxWithSubscript", s) - (u - supBox.depth)
                    if (delta > 0f) {
                        u += delta
                        v -= delta
                    }
                }
            }
            val placed = ArrayList<Placed>(3)
            placed += Placed(0f, 0f, base)
            // A large operator's advance already includes its italic correction (OpenType MATH): its superscript
            // goes at the advance and its subscript moves left. A letter's superscript moves right instead.
            val supX = base.width + if (isOp) 0f else italic
            val subX = base.width - if (isOp) italic else 0f
            var w = base.width + if (supBox == null || isOp) 0f else italic
            supBox?.let { placed += Placed(supX, -u, it); w = max(w, supX + it.width) }
            subBox?.let { placed += Placed(subX, v, it); w = max(w, subX + it.width) }
            return group(placed, w + c("SpaceAfterScript", s))
        }

        /** Limits centered above and below a large operator (∑ in display style, lim). */
        fun limits(op: Box, italic: Float, sup: MathNode.Row?, sub: MathNode.Row?, s: Style): Box {
            val upper = sup?.let { row(it, s.sup(), slot = true) }
            val lower = sub?.let { row(it, s.sub(), slot = true) }
            val w = max(op.width, max(upper?.width ?: 0f, lower?.width ?: 0f))
            val placed = ArrayList<Placed>(3)
            placed += Placed((w - op.width) / 2, 0f, op)
            upper?.let {
                val rise = op.height + max(c("UpperLimitGapMin", s) + it.depth, c("UpperLimitBaselineRiseMin", s))
                placed += Placed((w - it.width) / 2 + italic / 2, -rise, it)
            }
            lower?.let {
                val drop = op.depth + max(c("LowerLimitGapMin", s) + it.height, c("LowerLimitBaselineDropMin", s))
                placed += Placed((w - it.width) / 2 - italic / 2, drop, it)
            }
            return group(placed, w)
        }

        /** A large operator, centered on the axis (display size in display style); or an operator name. */
        fun bigOp(n: MathNode.BigOp, s: Style): Pair<Box, Float> {
            if (n.name) {
                val words = n.symbol.split(' ').filter { it.isNotEmpty() }
                val parts = ArrayList<Box>()
                words.forEachIndexed { k, w ->
                    if (k > 0) parts += kern(em(s) * THIN_SPACE)
                    parts += text(w, s, bold = false, italic = false)
                }
                return hbox(parts) to 0f
            }
            val base = font.glyphFor(n.symbol.codePointAt(0)) ?: return text(n.symbol, s, bold = false, italic = false) to 0f
            var g = base
            if (s.display) {
                val min = font.constant("DisplayOperatorMinHeight")
                val variants = font.verticalVariants(base.id)
                val pick = variants.firstOrNull { it.advance >= min } ?: variants.lastOrNull()
                if (pick != null) g = font.glyph(pick.glyph)
            }
            val box = GlyphBox(g, scale(s))
            val center = (box.height - box.depth) / 2
            val dy = center - axis(s)
            return group(listOf(Placed(0f, dy, box)), box.width) to box.italic
        }

        // ---- delimiters ----------------------------------------------------------------------------------------

        fun fenced(n: MathNode.Fenced, s: Style): Box = fence(n.left, n.parts.map { row(it, s) }, n.middles, n.right, s)

        fun fence(left: String, parts: List<Box>, middles: List<String>, right: String, s: Style): Box {
            val axis = axis(s)
            var extent = 0f
            parts.forEach { extent = max(extent, max(it.height - axis, it.depth + axis)) }
            val target = max(2 * extent * DELIMITER_FACTOR, 2 * extent - DELIMITER_SHORTFALL * em(s))
            val boxes = ArrayList<Box>()
            boxes += delimiter(left, target, s)
            parts.forEachIndexed { k, p ->
                if (k > 0) boxes += delimiter(middles.getOrElse(k - 1) { "" }, target, s)
                boxes += p
            }
            boxes += delimiter(right, target, s)
            return hbox(boxes)
        }

        /** Delimiter [d] at least [target] tall, centered on the axis; "" is the empty delimiter. */
        fun delimiter(d: String, target: Float, s: Style): Box {
            if (d.isEmpty()) return kern(em(s) * NULL_DELIMITER)
            val g = font.glyphFor(d.codePointAt(0)) ?: return text(d, s, bold = false, italic = false)
            val b = stretch(g, target, s, vertical = true)
            val dy = (b.height - b.depth) / 2 - axis(s)
            return group(listOf(Placed(0f, dy, b)), b.width)
        }

        /** [g] grown to [target] points along its stretch direction: the first size variant long enough, else an assembly. */
        fun stretch(g: Glyph, target: Float, s: Style, vertical: Boolean): Box {
            val sc = scale(s)
            val variants = if (vertical) font.verticalVariants(g.id) else font.horizontalVariants(g.id)
            variants.firstOrNull { it.advance * sc >= target }?.let { return GlyphBox(font.glyph(it.glyph), sc) }
            val asm = (if (vertical) font.verticalAssembly(g.id) else font.horizontalAssembly(g.id))
                ?: return GlyphBox(variants.lastOrNull()?.let { font.glyph(it.glyph) } ?: g, sc)
            return assemble(asm, target / sc, sc, vertical)
        }

        /** Builds a glyph assembly at least [length] font units long (parts bottom-to-top or left-to-right). */
        fun assemble(a: Assembly, length: Float, sc: Float, vertical: Boolean): Box {
            val overlap = font.minConnectorOverlap.toFloat()
            var repeats = 0
            var parts: List<AssemblyPart>
            while (true) {
                parts = a.parts.flatMap { p -> if (p.extender) List(repeats) { p } else listOf(p) }
                val longest = parts.sumOf { it.fullAdvance } - overlap * (parts.size - 1)
                if (longest >= length || repeats >= MAX_REPEATS || a.parts.none { it.extender }) break
                repeats++
            }
            val joins = parts.size - 1
            val spare = parts.sumOf { it.fullAdvance } - length
            val each = if (joins > 0) (spare / joins).coerceAtLeast(overlap) else 0f
            val placed = ArrayList<Placed>(parts.size)
            var pos = 0f
            parts.forEachIndexed { k, p ->
                val glyph = font.glyph(p.glyph)
                if (k > 0) {
                    val prev = parts[k - 1]
                    val maxOverlap = min(prev.endConnector, p.startConnector).toFloat().coerceAtLeast(overlap)
                    pos -= min(each, maxOverlap)
                }
                placed += if (vertical) Placed(0f, -pos * sc, GlyphBox(glyph, sc)) else Placed(pos * sc, 0f, GlyphBox(glyph, sc))
                pos += p.fullAdvance
            }
            val width = if (vertical) parts.maxOf { font.glyph(it.glyph).advance } * sc else pos * sc
            return group(placed, width)
        }

        // ---- accents, lines, braces, stacks ----------------------------------------------------------------------

        fun accent(n: MathNode.Accent, s: Style): Box {
            val single = (n.body.items.singleOrNull() as? MathNode.Sym)?.takeIf { it.text.codePointCount(0, it.text.length) == 1 }
            val (body, bodyItalic) = if (single != null) sym(single, s.cramp(), withItalic = true) else row(n.body, s.cramp(), slot = true) to 0f
            val base = font.glyphFor(n.accent) ?: return body
            val sc = scale(s)
            val accent: Box = when {
                !n.stretchy -> GlyphBox(base, sc)
                // Arrows reach across the whole body; hats and tildes take the widest size that is not wider.
                n.accent in ARROW_ACCENTS -> stretch(base, body.width, s, vertical = false)
                else -> GlyphBox(font.glyph((font.horizontalVariants(base.id).lastOrNull { it.advance * sc <= body.width } ?: Variant(base.id, 0)).glyph), sc)
            }
            val accentGlyph = (accent as? GlyphBox)?.glyph
            val accentAttach = accentGlyph?.let { g -> (g.topAccent ?: ((g.xMin + g.xMax) / 2)) * sc } ?: (accent.width / 2)
            val bodyGlyph = (body as? GlyphBox)?.glyph ?: ((body as? HBox)?.items?.firstOrNull()?.box as? GlyphBox)?.glyph?.takeIf { single != null }
            val bodyAttach = bodyGlyph?.topAccent?.let { it * sc } ?: ((body.width - bodyItalic) / 2)
            val raise = max(0f, body.height - c("AccentBaseHeight", s))
            val placed = listOf(Placed(0f, 0f, body), Placed(bodyAttach - accentAttach, -raise, accent))
            return group(placed, body.width)
        }

        fun line(n: MathNode.Line, s: Style): Box {
            val body = row(n.body, if (n.over) s.cramp() else s, slot = true)
            return if (n.over) {
                val t = c("OverbarRuleThickness", s)
                val gap = c("OverbarVerticalGap", s)
                val g = group(listOf(Placed(0f, 0f, body), Placed(0f, -(body.height + gap), RuleBox(body.width, t))), body.width)
                HBox(g.items, g.width, g.height + c("OverbarExtraAscender", s), g.depth)
            } else {
                val t = c("UnderbarRuleThickness", s)
                val gap = c("UnderbarVerticalGap", s)
                val g = group(listOf(Placed(0f, 0f, body), Placed(0f, body.depth + gap + t, RuleBox(body.width, t))), body.width)
                HBox(g.items, g.width, g.height, g.depth + c("UnderbarExtraDescender", s))
            }
        }

        fun brace(n: MathNode.Brace, s: Style): Box {
            val body = row(n.body, s, slot = true)
            val glyph = font.glyphFor(if (n.over) OVER_BRACE else UNDER_BRACE) ?: return body
            val b = stretch(glyph, body.width, s, vertical = false)
            val gap = c("OverbarVerticalGap", s)
            val bx = (body.width - b.width) / 2
            // Over: the brace's bottom sits a gap above the body. Under: its top sits a gap below.
            val by = if (n.over) -(body.height + gap) - b.depth else body.depth + gap + b.height
            val placed = arrayListOf(Placed(0f, 0f, body), Placed(bx, by, b))
            val withBrace = group(placed, max(body.width, b.width))
            val label = n.label ?: return withBrace
            val l = row(label, s.sup(), slot = true)
            val lx = (withBrace.width - l.width) / 2
            val ly = if (n.over) -(withBrace.height + gap + l.depth) else withBrace.depth + gap + l.height
            return group(withBrace.items + Placed(lx, ly, l), max(withBrace.width, l.width))
        }

        fun stack(n: MathNode.Stack, s: Style): Box {
            val base = row(n.base, s, slot = true)
            return limits(base, 0f, n.over, n.under, s)
        }

        fun cancel(n: MathNode.Cancel, s: Style): Box {
            val body = row(n.body, s, slot = true)
            val t = c("FractionRuleThickness", s)
            val pad = em(s) * CANCEL_OVERSHOOT
            val w = body.width
            val h = max(body.height, em(s) * STRUT_H * 0.7f)
            val d = max(body.depth, 0f)
            val placed = arrayListOf(Placed(0f, 0f, body))
            if (n.slash) placed += Placed(-pad, d + pad, LineBox(w + 2 * pad, -(h + d + 2 * pad), t))
            if (n.back) placed += Placed(-pad, -(h + pad), LineBox(w + 2 * pad, h + d + 2 * pad, t))
            val g = group(placed, w)
            val to = n.to ?: return HBox(g.items, g.width, h + pad, d + pad)
            val l = row(to, s.sup(), slot = true)
            val withTo = group(g.items + Placed(w + pad + em(s) * 0.1f, -(h + pad) + l.depth, l), w + pad + em(s) * 0.1f + l.width)
            return HBox(withTo.items, withTo.width, max(h + pad, withTo.height), d + pad)
        }

        fun boxed(n: MathNode.Boxed, s: Style): Box {
            val body = row(n.body, s, slot = true)
            val t = c("FractionRuleThickness", s)
            val pad = em(s) * BOX_PADDING
            val w = body.width + 2 * pad
            val top = body.height + pad
            val bottom = body.depth + pad
            val h = top + bottom
            return group(
                listOf(
                    Placed(pad, 0f, body),
                    Placed(0f, -top + t, RuleBox(w, t)),
                    Placed(0f, bottom, RuleBox(w, t)),
                    Placed(0f, bottom, RuleBox(t, h)),
                    Placed(w - t, bottom, RuleBox(t, h)),
                ),
                w,
            )
        }

        // ---- matrices and lines ----------------------------------------------------------------------------------

        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun matrix(n: MathNode.Matrix, outer: Style): Box {
            val cellStyle = when (n.kind) {
                MatrixKind.Aligned, MatrixKind.Gathered -> Style(if (outer.level == 0) 0 else outer.level, outer.cramped)
                MatrixKind.SmallMatrix -> Style(2, outer.cramped)
                else -> Style(max(1, outer.level), outer.cramped)
            }
            val e = em(cellStyle)
            val cols = n.rows.maxOfOrNull { it.size } ?: 0
            val cells = n.rows.map { r ->
                List(cols) { k ->
                    val cell = r.getOrNull(k) ?: MathNode.Row(emptyList())
                    // In aligned columns a relation starting the right-hand cell keeps its space ("&= x").
                    if (n.kind == MatrixKind.Aligned && k % 2 == 1 && cell.items.isNotEmpty()) {
                        row(listOf(MathNode.Row(emptyList())) + cell.items, cellStyle)
                    } else {
                        row(cell, cellStyle)
                    }
                }
            }
            val strutH = e * if (n.kind == MatrixKind.Cases) CASES_STRUT_H else STRUT_H
            val strutD = e * if (n.kind == MatrixKind.Cases) CASES_STRUT_D else STRUT_D
            val rowGap = e * when (n.kind) {
                MatrixKind.Aligned, MatrixKind.Gathered -> JOT
                else -> 0f
            }
            val heights = cells.map { r -> max(strutH, r.maxOfOrNull { it.height } ?: 0f) }
            val depths = cells.map { r -> max(strutD, r.maxOfOrNull { it.depth } ?: 0f) }
            val widths = List(cols) { k -> cells.maxOfOrNull { it[k].width } ?: 0f }
            fun align(k: Int): Char = when (n.kind) {
                MatrixKind.Cases -> 'l'
                MatrixKind.Aligned -> if (k % 2 == 0) 'r' else 'l'
                MatrixKind.Array -> n.columns.getOrElse(k) { 'c' }
                else -> 'c'
            }
            fun gapBefore(k: Int): Float = if (k == 0) 0f else e * when (n.kind) {
                MatrixKind.Aligned -> if (k % 2 == 1) 0f else ALIGN_PAIR_GAP
                MatrixKind.SmallMatrix -> SMALL_COLUMN_GAP
                else -> COLUMN_GAP
            }
            val xs = FloatArray(cols)
            var x = 0f
            for (k in 0 until cols) {
                x += gapBefore(k)
                xs[k] = x
                x += widths[k]
            }
            val totalW = x
            val totalH = heights.sum() + depths.sum() + rowGap * (cells.size - 1).coerceAtLeast(0)
            val axis = axis(outer)
            // Center the block on the axis (a formula of several lines: its first baseline stays near the top).
            var y = -(axis + totalH / 2)
            val placed = ArrayList<Placed>()
            cells.forEachIndexed { r, rowCells ->
                y += heights[r]
                rowCells.forEachIndexed { k, cell ->
                    val cx = when (align(k)) {
                        'l' -> xs[k]
                        'r' -> xs[k] + widths[k] - cell.width
                        else -> xs[k] + (widths[k] - cell.width) / 2
                    }
                    placed += Placed(cx, y, cell)
                }
                y += depths[r] + rowGap
            }
            val top = axis + totalH / 2
            val body = HBox(placed, totalW, top, totalH - top)
            if (n.left.isEmpty() && n.right.isEmpty()) return body
            val padded = if (n.kind == MatrixKind.Cases) hbox(listOf(body, kern(e * NULL_DELIMITER))) else body
            return fence(n.left, listOf(padded), emptyList(), n.right, outer)
        }
    }

    // ---- flattening --------------------------------------------------------------------------------------------

    private fun flatten(box: Box, errors: List<LatexError>): MathLayout {
        val ops = ArrayList<MathOp>()
        fun walk(b: Box, x: Float, y: Float, tone: Tone) {
            when (b) {
                is GlyphBox -> if (b.glyph.path.ops.isNotEmpty()) ops += MathOp.GlyphOp(b.glyph, x, y, b.scale, tone)
                is RuleBox -> ops += MathOp.RuleOp(x, y - b.height, b.width, b.height + b.depth, tone)
                is TextBox -> ops += MathOp.TextOp(b.text, x, y, b.size, b.bold, b.italic, tone, b.rtl)
                is LineBox -> ops += MathOp.LineOp(x, y, x + b.dx, y + b.dy, b.thickness, tone)
                is HBox -> {
                    val t = b.tone ?: tone
                    for (p in b.items) walk(p.box, x + p.x, y + p.y, t)
                }
            }
        }
        walk(box, 0f, 0f, Tone.Normal)
        return MathLayout(box.width, max(0f, box.height), max(0f, box.depth), ops, errors)
    }

    private companion object {
        const val PERCENT = 100f
        const val MU_PER_EM = 18f

        /** Thin, medium and thick math spaces in mu. */
        val MU = floatArrayOf(0f, 3f, 4f, 5f)

        /**
         * TeX's inter-atom spacing (TeXbook p. 170): rows = left atom, columns = right atom, in [AtomKind] order.
         * 1 thin, 2 medium, 3 thick; negative = only in display and text style.
         */
        val SPACING = arrayOf(
            intArrayOf(0, 1, -2, -3, 0, 0, 0, -1), // Ord
            intArrayOf(1, 1, 0, -3, 0, 0, 0, -1), // Op
            intArrayOf(-2, -2, 0, 0, -2, 0, 0, -2), // Bin
            intArrayOf(-3, -3, 0, 0, -3, 0, 0, -3), // Rel
            intArrayOf(0, 0, 0, 0, 0, 0, 0, 0), // Open
            intArrayOf(0, 1, -2, -3, 0, 0, 0, -1), // Close
            intArrayOf(-1, -1, 0, -1, -1, -1, -1, -1), // Punct
            intArrayOf(-1, 1, -2, -3, -1, 0, -1, -1), // Inner
        )
        val BIN_BREAKERS = setOf(AtomKind.Bin, AtomKind.Op, AtomKind.Rel, AtomKind.Open, AtomKind.Punct)
        val BIN_ENDERS = setOf(AtomKind.Rel, AtomKind.Close, AtomKind.Punct)
        val ITALIC_ALPHABETS = setOf(Alphabet.Default, Alphabet.Italic, Alphabet.BoldItalic)

        const val NULL_DELIMITER = 0.12f
        const val DELIMITER_FACTOR = 0.901f
        const val DELIMITER_SHORTFALL = 0.5f
        const val THIN_SPACE = 3f / 18f
        val BIG_SIZES = floatArrayOf(1.2f, 1.8f, 2.4f, 3.0f)
        const val STRUT_H = 0.85f
        const val STRUT_D = 0.35f
        const val CASES_STRUT_H = 1.0f
        const val CASES_STRUT_D = 0.42f
        const val JOT = 0.45f
        const val COLUMN_GAP = 1f
        const val SMALL_COLUMN_GAP = 0.5f
        const val ALIGN_PAIR_GAP = 2f
        const val BOX_PADDING = 0.3f
        const val CANCEL_OVERSHOOT = 0.08f
        const val MAX_REPEATS = 200
        const val RADICAL = 0x221A
        const val OVER_BRACE = 0x23DE
        const val UNDER_BRACE = 0x23DF
        const val PLACEHOLDER = 0x25A1
        val ARROW_ACCENTS = setOf(0x20D7, 0x20D6, 0x20E1)
        const val RTL_START = 0x0590
        const val RTL_END = 0x08FF
    }
}
