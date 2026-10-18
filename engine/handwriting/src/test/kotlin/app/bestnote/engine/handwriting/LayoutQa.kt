package app.bestnote.engine.handwriting

import app.bestnote.core.model.RectPt
import app.bestnote.core.model.Tool
import app.bestnote.engine.layout.qa.InkBox
import app.bestnote.engine.layout.qa.InkKind
import app.bestnote.engine.layout.qa.LayoutIssue
import app.bestnote.engine.layout.qa.OverlapDetector
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.text.AttributedString
import javax.imageio.ImageIO

/**
 * Layout QA for typeset Smart Board pages: every run's REAL glyph ink (java.awt with the bundled
 * fonts, as Android draws them) and every pen stroke → [InkBox]es → [OverlapDetector]; failing pages
 * can be written to PNG with the problems outlined in red.
 */
internal object LayoutQa {
    private val frc = FontRenderContext(null, true, true)
    private val detector = OverlapDetector()
    private val cache = HashMap<String, Rectangle2D?>()

    private fun layout(r: TypeRun, scale: Float = 1f): TextLayout? {
        if (r.text.isBlank()) return null
        val font = AwtMeasure.font(r.font).deriveFont(r.sizePt * scale)
        val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(r.sizePt * scale)
        val text = AttributedString(r.text)
        text.addAttribute(TextAttribute.RUN_DIRECTION, if (r.rtl) TextAttribute.RUN_DIRECTION_RTL else TextAttribute.RUN_DIRECTION_LTR)
        for ((i, c) in r.text.withIndex()) text.addAttribute(TextAttribute.FONT, if (font.canDisplay(c)) font else fallback, i, i + 1)
        return TextLayout(text.iterator, frc)
    }

    /** Glyph ink of a run on the page (null: nothing visible). */
    fun inkOf(r: TypeRun): RectPt? {
        val key = "${r.font.id}|${r.sizePt}|${r.rtl}|${r.text}"
        val b = cache.getOrPut(key) {
            val l = layout(r) ?: return@getOrPut null
            val bounds = l.bounds
            if (bounds.isEmpty) null else Rectangle2D.Float(bounds.x.toFloat() - (if (r.rtl) l.advance else 0f), bounds.y.toFloat(), bounds.width.toFloat(), bounds.height.toFloat())
        } ?: return null
        val x = if (r.rtl) r.left + r.width else r.left
        return RectPt(x + b.x.toFloat(), r.baseline + b.y.toFloat(), x + (b.x + b.width).toFloat(), r.baseline + (b.y + b.height).toFloat())
    }

    private val outlineCache = HashMap<String, Area>()

    /** The run's glyph outlines on the page: its REAL ink, as filled by the renderer. */
    private fun outlineOf(r: TypeRun): Area {
        val key = "${r.font.id}|${r.sizePt}|${r.rtl}|${r.text}"
        val rel = outlineCache.getOrPut(key) {
            val l = layout(r) ?: return@getOrPut Area()
            Area(l.getOutline(AffineTransform.getTranslateInstance(if (r.rtl) -l.advance.toDouble() else 0.0, 0.0)))
        }
        val x = if (r.rtl) r.left + r.width else r.left
        return rel.createTransformedArea(AffineTransform.getTranslateInstance(x.toDouble(), r.baseline.toDouble()))
    }

    /** How far two pieces of ink really overlap (pt): glyph outlines against glyph outlines or a stroke piece. */
    private fun realOverlap(a: InkBox, b: InkBox): Float {
        fun area(i: InkBox) = runOf[i]?.let(::outlineOf) ?: Area(Rectangle2D.Float(i.rect.left, i.rect.top, i.rect.width, i.rect.height))
        val x = area(a)
        x.intersect(area(b))
        if (x.isEmpty) return 0f
        val r = x.bounds2D
        return minOf(r.width, r.height).toFloat()
    }

    fun boxes(pages: NotePages, page: Int): List<InkBox> {
        val frames = HashMap<Pair<String, Int>, RectPt>()
        val targets = pages.placed.mapNotNull { (_, p) -> p.targetKey?.let { p.key to it } }.toMap()
        for ((pi, p) in pages.placed) if (pi == page) {
            val n = p.rows?.let { it.last + 1 } ?: 2048
            for (ri in (p.rows?.first ?: 0) until n) frames[p.key to ri] = p.frame
            if (p.rows == null) frames[p.key to -2] = p.frame
        }
        // Ink drawn outside the element's rows (part -1: a margin note's arrow to its target) may leave the frame.
        fun frameOf(owner: String?, part: Int) = if (part < 0) null else owner?.let { frames[it to part] ?: frames[it to -2] }
        val tp = pages.pages.pages[page]
        val out = ArrayList<InkBox>()
        runOf.clear()
        for (r in tp.runs) {
            val ink = inkOf(r) ?: continue
            val box = InkBox(r.block, r.part, InkKind.Text, ink, r.sizePt, r.text, frameOf(r.block, r.part))
            runOf[box] = r
            out += box
        }
        // Typeset formulas (MATH-02) by their box metrics; pictures (AI-12) by their box.
        for (q in tp.equations) {
            out += InkBox(q.block.ifEmpty { null }, q.part, InkKind.Text, RectPt(q.left, q.baseline - q.ascent, q.left + q.width, q.baseline + q.descent), q.sizePt, q.latex, frameOf(q.block.ifEmpty { null }, q.part))
        }
        for (im in tp.images) {
            out += InkBox(im.block, -1, InkKind.Stroke, RectPt(im.left, im.top, im.left + im.width, im.top + im.height), label = "image")
        }
        for ((i, s) in tp.ink.withIndex()) {
            if (s.brush.tool == Tool.Highlighter) continue
            val (owner, part) = tp.inkOwners.getOrNull(i) ?: (null to -1)
            val rule = owner == NoteComposer.RULES
            val xs = FloatArray(s.samples.size) { s.samples[it].x }
            val ys = FloatArray(s.samples.size) { s.samples[it].y }
            for (b in OverlapDetector.strokeBoxes(xs, ys, s.brush.size * 0.9f)) {
                out += InkBox(if (rule) null else owner, part, if (rule) InkKind.Rule else InkKind.Stroke, b, frame = if (rule) null else frameOf(owner, part), label = if (rule) "rule" else "stroke", target = if (part < 0) targets[owner] else null)
            }
        }
        return out
    }

    private val runOf = java.util.IdentityHashMap<InkBox, TypeRun>()

    fun check(pages: NotePages): List<LayoutIssue> = pages.pages.pages.indices.flatMap { p ->
        detector.check(p, boxes(pages, p), RectPt(0f, 0f, pages.paper.width, pages.paper.height), ::realOverlap)
    }

    /** The page with every issue outlined (red: overlap, orange: overflow/clipped, blue: tiny), under build/layout-qa. */
    fun png(pages: NotePages, page: Int, issues: List<LayoutIssue>, name: String, scale: Float = 2f) {
        val paper = pages.paper
        val img = BufferedImage((paper.width * scale).toInt(), (paper.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color(0xDCE4F0)
        for (i in 0 until paper.lineCount) g.draw(Line2D.Float(0f, paper.lineY(i) * scale, img.width.toFloat(), paper.lineY(i) * scale))
        val tp = pages.pages.pages[page]
        for (s in tp.ink) {
            g.color = Color(s.brush.colorArgb, true)
            g.stroke = BasicStroke(s.brush.size * scale * 0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val pts = s.samples
            for (i in 0 until pts.size - 1) g.draw(Line2D.Float(pts[i].x * scale, pts[i].y * scale, pts[i + 1].x * scale, pts[i + 1].y * scale))
        }
        for (r in tp.runs) {
            val l = layout(r, scale) ?: continue
            g.color = Color(r.color, true)
            l.draw(g, if (r.rtl) (r.left + r.width) * scale - l.advance else r.left * scale, r.baseline * scale)
        }
        g.stroke = BasicStroke(1.5f)
        for (i in issues.filter { it.page == page }) {
            g.color = when (i.type) {
                LayoutIssue.Type.Overlap -> Color.RED
                LayoutIssue.Type.Tiny -> Color.BLUE
                else -> Color.ORANGE
            }
            for (b in listOfNotNull(i.a, i.b)) g.draw(Rectangle2D.Float(b.rect.left * scale, b.rect.top * scale, b.rect.width * scale, b.rect.height * scale))
        }
        g.dispose()
        val dir = File("build/layout-qa").apply { mkdirs() }
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }
}
