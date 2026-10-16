package app.bestnote.engine.handwriting

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Letter forms: [Print] lifts the pen after every glyph (neat print, formulas); [Cursive] joins lowercase letters. */
enum class Script { Print, Cursive }

/**
 * The writing line under the pen: a ruled baseline plus the small drift and slope of a real hand.
 * Port of `Pen` in tools/handwriting/hw/latin.py.
 */
internal class Baseline(private val hand: Hand, x: Float, private var base0: Float, steady: Boolean = false) {
    var x: Float = x
    private var lineStart = x
    private var drift: SmoothNoise? = null
    private var slope = 0f

    init {
        reset(steady)
    }

    private fun reset(steady: Boolean) {
        if (steady) {
            drift = null
            slope = 0f
        } else {
            drift = SmoothNoise(hand.rnd, 60f * hand.unitsPerMm, 0.1f * hand.xh * hand.jitter, octaves = 2)
            slope = Math.toRadians(hand.rnd.gauss(0f, 0.35f * hand.jitter).toDouble()).toFloat()
        }
    }

    fun at(px: Float): Float = base0 + (drift?.invoke(px) ?: 0f) + (px - lineStart) * tan(slope)
}

/**
 * Writes Latin, Greek/maths symbols and Arabic as pen strokes into an [InkBuilder]. Lengths are page
 * units; the x-height comes from the [Hand]. Every method is deterministic for a given hand seed.
 *
 * Latin is a port of tools/handwriting/hw/latin.py (Hershey single-stroke fonts with human jitter),
 * Arabic places the exported Aref Ruqaa contextual forms right to left.
 */
internal class TextWriter(private val hand: Hand, private val ink: InkBuilder) {
    private val mm get() = hand.unitsPerMm

    /** Writes the inline maths tokens ([InlineMath.encode]) inside words; null writes them as plain text. */
    var inline: InlineRenderer? = null

    // ------------------------------------------------------------------ measuring

    /** Width of [text] (words and spaces) at [scale], without jitter. */
    fun measure(text: String, scale: Float = 1f, script: Script = Script.Cursive): Float {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return 0f
        return words.sumOf { measureWord(it, scale, script).toDouble() }.toFloat() + (words.size - 1) * spaceWidth(scale)
    }

    fun spaceWidth(scale: Float = 1f) = 1.05f * hand.xh * scale

    fun measureWord(word: String, scale: Float = 1f, script: Script = Script.Cursive): Float {
        if (InlineMath.hasToken(word)) {
            return InlineMath.segments(word).sumOf { (token, part) ->
                (if (token != null) inline?.measure(token, scale) ?: measurePlain(part, scale, script) else measurePlain(part, scale, script)).toDouble()
            }.toFloat()
        }
        return measurePlain(word, scale, script)
    }

    private fun measurePlain(word: String, scale: Float, script: Script): Float {
        if (word.isEmpty()) return 0f
        if (Arabic.isArabic(word)) return Arabic.measure(word, arabicEm(scale))
        var total = 0f
        for (ch in word) total += advanceOf(ch, scale, script)
        return total
    }

    private fun advanceOf(ch: Char, scale: Float, script: Script): Float {
        Symbols[ch]?.let { return it.advance * hand.xh * scale }
        val (font, glyph) = glyphFor(ch, script)
        val u = hand.xh * scale / font.xHeightUnits
        return glyph.width * u * (if (font === GlyphData.print) 0.9f else 1f)
    }

    private fun glyphFor(ch: Char, script: Script): Pair<HersheyFont, HGlyph> {
        val first = if (script == Script.Cursive) GlyphData.cursive else GlyphData.print
        first.glyphs[ch]?.let { return first to it }
        val print = GlyphData.print
        // Never a "?" for a missing letter: an empty slot (callers switch to the font writer first, see StrokeHand).
        return print to (print.glyphs[ch] ?: print.glyphs[' '] ?: print.glyphs.getValue('?'))
    }

    /** Arabic em size for a Latin x-height scale: Ruqaa letters sit about 3.4 x-heights per em. */
    fun arabicEm(scale: Float) = hand.xh * 3.4f * scale

    /** Greedy word wrap into lines no wider than [width]. A word wider than the line gets a line of its own. */
    fun wrap(text: String, width: Float, scale: Float = 1f, script: Script = Script.Cursive, firstIndent: Float = 0f): List<String> {
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        var curW = 0f
        val space = spaceWidth(scale)
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val w = measureWord(word, scale, script)
            val limit = if (lines.isEmpty()) width - firstIndent else width
            if (cur.isNotEmpty() && curW + space + w > limit) {
                lines += cur.toString()
                cur = StringBuilder()
                curW = 0f
            }
            if (cur.isNotEmpty()) {
                cur.append(' ')
                curW += space
            }
            cur.append(word)
            curW += w
        }
        if (cur.isNotEmpty()) lines += cur.toString()
        return lines
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes one line of [text] on the ruled line at [baseline]. LTR text starts at [x]; with [rtl]
     * the line starts at [x] and runs leftwards.
     *
     * Mixed directions follow the Unicode bidi idea at word level: Arabic words form right-to-left
     * runs, Latin words, numbers and inline maths form left-to-right runs, and neutral words
     * (dashes, ellipses, lone operators) take the direction of their neighbours when both agree,
     * else the line's. Runs are laid in reading order from the line's start; inside a run, words
     * keep their own direction, so "if d > 0" stays readable inside an Arabic line and an Arabic
     * phrase reads right to left inside an English one.
     * @return the x where the pen stopped (right end for LTR, left end for RTL).
     */
    fun writeLine(
        text: String,
        x: Float,
        baseline: Float,
        color: Int,
        scale: Float = 1f,
        script: Script = Script.Cursive,
        rtl: Boolean = false,
        firstGap: Gap = Gap.Line,
        steady: Boolean = false,
    ): Float {
        val line = Baseline(hand, x, baseline, steady)
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return x
        val space = spaceWidth(scale)
        var gap = firstGap
        var first = true
        for (run in Bidi.runs(words, rtl)) {
            val widths = run.words.map { measureWord(it, scale, script) }
            val total = widths.sum() + space * (run.words.size - 1)
            if (!first) line.x += if (rtl) -space else space
            first = false
            val left = if (rtl) line.x - total else line.x
            if (run.rtl) {
                // Right to left from the run's right end.
                var right = left + total
                for ((i, word) in run.words.withIndex()) {
                    writeToken(word, right - widths[i], line.at(right - widths[i]), widths[i], color, scale, script, gap, rtlWord = true)
                    gap = Gap.Word
                    right -= widths[i] + space
                }
            } else if (!rtl && run.words.size == words.size) {
                // A plain LTR line: one continuous baseline with the hand's drift.
                for ((i, word) in run.words.withIndex()) {
                    if (i > 0) line.x += space * hand.rnd.uniform(0.9f, 1.15f)
                    writeTokenOn(line, word, color, scale, script, gap)
                    gap = Gap.Word
                }
                return line.x
            } else {
                var cx = left
                for ((i, word) in run.words.withIndex()) {
                    writeToken(word, cx, line.at(cx), widths[i], color, scale, script, gap, rtlWord = false)
                    gap = Gap.Word
                    cx += widths[i] + space
                }
            }
            line.x = if (rtl) left else left + total
        }
        return line.x
    }

    /** Writes one word whose box starts at [left] and is [width] wide. */
    private fun writeToken(word: String, left: Float, base: Float, width: Float, color: Int, scale: Float, script: Script, gap: Gap, rtlWord: Boolean) {
        if (rtlWord && Arabic.isArabic(word) && !InlineMath.hasToken(word)) {
            writeArabicWord(word, left + width, base, color, scale, gap)
            return
        }
        writeTokenOn(Baseline(hand, left, base, steady = true), word, color, scale, script, gap)
    }

    /** Writes a word left to right at the line's x, inline maths and Arabic pieces included, and advances it. */
    private fun writeTokenOn(line: Baseline, word: String, color: Int, scale: Float, script: Script, firstGap: Gap) {
        val renderer = inline
        if (!InlineMath.hasToken(word) || renderer == null) {
            if (Arabic.isArabic(word)) {
                val w = measurePlain(word, scale, script)
                writeArabicWord(word, line.x + w, line.at(line.x), color, scale, firstGap)
                line.x += w
            } else {
                writeWord(line, InlineMath.stripTokens(word), color, scale, script, firstGap)
            }
            return
        }
        var gap = firstGap
        for ((token, part) in InlineMath.segments(word)) {
            if (token != null) {
                line.x = renderer.write(token, line.x, line.at(line.x), color, scale, gap)
            } else if (part.isNotEmpty()) {
                writeTokenOn(line, part, color, scale, script, gap)
            }
            gap = Gap.Letter
        }
    }

    /** Writes [word] at the line's x and advances it. Port of `write_word`. */
    fun writeWord(line: Baseline, word: String, color: Int, scale: Float, script: Script, firstGap: Gap, speed: Float = 1f) {
        val rnd = hand.rnd
        val xh = hand.xh
        val slant = tan(Math.toRadians((hand.slantDeg + rnd.gauss(0f, 2f * hand.jitter)).toDouble())).toFloat()
        val out = ArrayList<Triple<Boolean, Poly, Gap>>() // (isDot, points, gap)
        val later = ArrayList<Pair<Boolean, Poly>>()
        var chain: MutableList<P>? = null
        var chainChar: Char? = null
        var gap = firstGap
        for (ch in word) {
            val x0 = line.x
            val base = line.at(x0)
            fun place(pts: Poly): Poly = pts.map { P(x0 + it.x - it.y * slant, base + it.y) }
            val g = glyph(ch, scale, script)
            val joinable = script == Script.Cursive && ch.isLetter() && !Symbols.has(ch)
            for ((si, s) in g.strokes.withIndex()) {
                val pts = place(s)
                val c = chain
                if (c != null) {
                    val d = Geom.dist(c.last(), pts[0])
                    val sameGlyph = si > 0
                    val lower = ch.isLowerCase() && (chainChar?.isLetter() == true)
                    if ((sameGlyph && (ch.isLowerCase() || d < 0.15f * xh)) || (!sameGlyph && joinable && lower && d < 1.3f * xh)) {
                        if (d < 0.05f * xh) c.addAll(pts.drop(1)) else c.addAll(pts)
                        chainChar = ch
                        continue
                    }
                    out += Triple(false, c, gap)
                    gap = Gap.Letter
                }
                chain = pts.toMutableList()
                chainChar = ch
                if (!joinable) {
                    out += Triple(false, pts, gap)
                    gap = Gap.Letter
                    chain = null
                }
            }
            for ((isDot, pts) in g.deferred) later += isDot to place(pts)
            for (d in g.dots) {
                chain?.let { out += Triple(false, it, gap); gap = Gap.Letter; chain = null }
                out += Triple(true, listOf(place(listOf(d))[0]), gap)
                gap = Gap.Letter
            }
            val jitterAdv = rnd.gauss(0f, 0.04f * xh * hand.jitter)
            line.x = x0 + g.advance + (if (joinable) 0.4f * jitterAdv else jitterAdv)
        }
        chain?.let { out += Triple(false, it, gap) }
        for ((isDot, pts) in later) out += Triple(isDot, pts, Gap.Deferred)
        for ((isDot, pts, g) in out) {
            if (isDot || pts.size == 1) ink.dot(pts[0], g, color) else ink.add(finish(pts, scale, script), g, color, speed)
        }
    }

    private class Glyph(val strokes: List<Poly>, val deferred: List<Pair<Boolean, Poly>>, val dots: List<P>, val advance: Float)

    /** Jittered strokes for one character, relative to its origin on the baseline (y up is negative). */
    private fun glyph(ch: Char, scale: Float, script: Script): Glyph {
        val rnd = hand.rnd
        val xh = hand.xh * scale
        Symbols[ch]?.let { sg ->
            val sx = xh * (1 + rnd.gauss(0f, 0.03f * hand.jitter))
            val polys = sg.strokes.map { s -> s.map { P(it.x * sx, it.y * xh) } }
            return Glyph(polys.filter { it.size > 1 }, emptyList(), polys.filter { it.size == 1 }.map { it[0] }, sg.advance * xh)
        }
        val (font, g) = glyphFor(ch, script)
        val isPrint = font === GlyphData.print
        val u = xh / font.xHeightUnits
        val sx = u * (1 + rnd.gauss(0f, 0.04f * hand.jitter))
        val sy = u * (1 + rnd.gauss(0f, 0.045f * hand.jitter))
        val dy = rnd.gauss(0f, 0.035f * hand.xh * hand.jitter)
        val rot = Math.toRadians(rnd.gauss(0f, (if (isPrint) 2.5f else 1.3f) * hand.jitter).toDouble()).toFloat()
        val cr = cos(rot)
        val sr = sin(rot)
        val deferredIdx = if (script == Script.Cursive && !isPrint) DEFERRED[ch].orEmpty() else emptyList()
        val strokes = ArrayList<Poly>()
        val deferred = ArrayList<Pair<Boolean, Poly>>()
        for ((i, s) in g.strokes.withIndex()) {
            var pts = s.map { P(it.x * sx, (it.y - HersheyFont.BASELINE) * sy + dy) }
            pts = pts.map { P(it.x * cr - it.y * sr, it.x * sr + it.y * cr) }
            if (i in deferredIdx) {
                val ox = rnd.gauss(0.05f * hand.xh, 0.07f * hand.xh * hand.jitter)
                val oy = rnd.gauss(0f, 0.05f * hand.xh * hand.jitter)
                pts = pts.map { P(it.x + ox, it.y + oy) }
                if (Geom.length(pts) < 0.4f * xh) {
                    deferred += true to listOf(P(pts.map { it.x }.average().toFloat(), pts.map { it.y }.average().toFloat()))
                } else {
                    deferred += false to pts
                }
            } else {
                strokes += pts
            }
        }
        val adv = g.width * sx * (if (isPrint) 0.9f else 1f)
        return Glyph(strokes, deferred, emptyList(), adv)
    }

    /** Polyline → smooth, slightly wobbly dense pen path. */
    private fun finish(pts: Poly, scale: Float, script: Script): Poly {
        val dense = Geom.catmullRom(pts, 0.08f * mm)
        val amp = (if (script == Script.Print) 0.016f else 0.012f) * hand.xh * scale * hand.jitter + 0.008f * mm
        return Geom.wobble(dense, hand.rnd, amp, 1.6f * hand.xh * scale)
    }

    // ------------------------------------------------------------------ Arabic

    private fun writeArabicWord(word: String, right: Float, baseline: Float, color: Int, scale: Float, firstGap: Gap) {
        val rnd = hand.rnd
        val em = arabicEm(scale) * rnd.uniform(0.97f, 1.03f)
        val slant = tan(Math.toRadians(rnd.gauss(0f, 1.5f * hand.jitter).toDouble())).toFloat()
        val dy = rnd.gauss(0f, 0.04f * hand.xh)
        fun place(pts: Poly) = pts.map { P(right + it.x * em - it.y * em * slant, baseline + it.y * em + dy) }
        val (bodies, marks) = Arabic.strokes(word)
        var gap = firstGap
        for (b in bodies) {
            val dense = Geom.catmullRom(place(b), 0.08f * mm)
            ink.add(Geom.wobble(dense, rnd, 0.01f * hand.xh + 0.008f * mm, 1.8f * hand.xh), gap, color)
            gap = Gap.Letter
        }
        for (m in marks) {
            val pts = place(m).map { P(it.x + rnd.gauss(0f, 0.03f * hand.xh), it.y + rnd.gauss(0f, 0.03f * hand.xh)) }
            if (pts.size == 1 || Geom.length(pts) < 0.5f * mm) ink.dot(pts[0], Gap.Deferred, color) else ink.add(Geom.catmullRom(pts, 0.06f * mm), Gap.Deferred, color)
        }
    }

    private companion object {
        /** Strokes of the script font written after the word: i/j dots, t bar, the second stroke of x. */
        val DEFERRED = mapOf('i' to listOf(0), 'j' to listOf(0), 't' to listOf(2), 'x' to listOf(1))
    }
}

/**
 * Arabic words from the exported contextual forms: units are split (lam-alef ligatures kept
 * together), each gets its isolated/initial/medial/final form from its neighbours' joining, and
 * forms are laid right to left by their advances. Coordinates in em, origin at the word's right
 * end on the baseline, x growing to the right (so the word lies at x <= 0), y down.
 */
internal object Arabic {
    private fun isArabicChar(c: Char) = c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'

    fun isArabic(word: String) = word.any(::isArabicChar)

    /** Harakat and tatweel are dropped: notes are written without them. */
    private fun skip(c: Char) = c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ'

    private fun units(word: String): List<String> {
        val g = GlyphData.arabic
        val chars = word.filterNot(::skip)
        val out = ArrayList<String>()
        var i = 0
        while (i < chars.length) {
            if (i + 1 < chars.length) {
                val pair = chars.substring(i, i + 2)
                if (pair in ArabicGlyphs.LIGATURES && g.has(pair)) {
                    out += pair
                    i += 2
                    continue
                }
            }
            out += chars[i].toString()
            i++
        }
        return out
    }

    private fun forms(word: String): List<ArabicForm?> {
        val g = GlyphData.arabic
        val u = units(word)
        return u.mapIndexed { i, unit ->
            val prev = u.getOrNull(i - 1)
            val next = u.getOrNull(i + 1)
            val joinPrev = prev != null && g.joinsNext(prev) && g.joinsPrev(unit)
            val joinNext = next != null && g.joinsNext(unit) && g.joinsPrev(next)
            val form = when {
                joinPrev && joinNext -> "medi"
                joinPrev -> "fina"
                joinNext -> "init"
                else -> "isol"
            }
            g.form(unit, form) ?: latinFallback(unit)
        }
    }

    /**
     * Forms in the order they are laid from the right. Numbers read left to right even inside
     * Arabic ("١٢" is twelve), so each run of digits is reversed before laying right to left.
     */
    private fun visualOrder(word: String): List<ArabicForm?> {
        val u = units(word)
        val f = forms(word)
        val out = ArrayList<ArabicForm?>(f.size)
        var i = 0
        while (i < f.size) {
            if (isDigit(u[i])) {
                var j = i
                while (j < f.size && isDigit(u[j])) j++
                for (k in j - 1 downTo i) out += f[k]
                i = j
            } else {
                out += f[i++]
            }
        }
        return out
    }

    private fun isDigit(unit: String) = unit.length == 1 && (unit[0] in '٠'..'٩' || unit[0] in '۰'..'۹' || unit[0] in '0'..'9')

    /** Characters the Arabic set lacks (digits, punctuation) are skipped rather than failing the word. */
    private fun latinFallback(@Suppress("UNUSED_PARAMETER") unit: String): ArabicForm? = null

    fun measure(word: String, em: Float): Float = forms(word).sumOf { (it?.advance ?: 0.3f).toDouble() }.toFloat() * em

    /** (bodies, marks) in em, the word's right end at x = 0. Bodies right to left, then the marks. */
    fun strokes(word: String): Pair<List<Poly>, List<Poly>> {
        val bodies = ArrayList<Poly>()
        val marks = ArrayList<Poly>()
        var x = 0f
        for (f in visualOrder(word)) {
            if (f == null) {
                x -= 0.3f
                continue
            }
            bodies += f.bodies.map { b -> b.map { P(it.x + x, it.y) } }
            marks += f.marks.map { m -> m.map { P(it.x + x, it.y) } }
            x -= f.advance
        }
        return bodies to marks
    }
}
