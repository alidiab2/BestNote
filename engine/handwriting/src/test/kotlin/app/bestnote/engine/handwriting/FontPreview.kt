package app.bestnote.engine.handwriting

import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.paper.LineRole
import app.bestnote.engine.layout.paper.PaperGeometry
import app.bestnote.core.model.PageSize
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders a typeset page (real fonts) to build/font-preview/[name].png for eyeballing. */
internal object FontPreview {
    fun png(paper: RuledPaper, page: TypesetPage, name: String, scale: Float = 2f) {
        val img = BufferedImage((paper.width * scale).toInt(), (paper.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        val geo = PaperGeometry.of(paper.template, PageSize(paper.width, paper.height))
        for (l in geo.lines) {
            g.color = if (l.role == LineRole.Margin || l.role == LineRole.Divider) Color(0xE8A0A0) else Color(0xC9D6E8)
            g.draw(Line2D.Float(l.x0 * scale, l.y0 * scale, l.x1 * scale, l.y1 * scale))
        }
        geo.dots?.let { d -> g.color = Color(0xB0BCD0); for (j in 0 until d.rows) for (i in 0 until d.cols) g.fillOval(((d.left + i * d.spacing) * scale).toInt() - 1, ((d.top + j * d.spacing) * scale).toInt() - 1, 3, 3) }
        for (s in page.ink) {
            g.color = Color(s.brush.colorArgb, true)
            val pts = s.samples
            g.stroke = BasicStroke(s.brush.size * scale * 0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            for (i in 0 until pts.size - 1) g.draw(Line2D.Float(pts[i].x * scale, pts[i].y * scale, pts[i + 1].x * scale, pts[i + 1].y * scale))
        }
        for (r in page.runs) {
            g.color = Color(r.color, true)
            val font = AwtMeasure.font(r.font).deriveFont(r.sizePt * scale)
            val attrs = mapOf(TextAttribute.FONT to font, TextAttribute.RUN_DIRECTION to if (r.rtl) TextAttribute.RUN_DIRECTION_RTL else TextAttribute.RUN_DIRECTION_LTR)
            val layout = TextLayout(r.text, attrs, g.fontRenderContext)
            val x = if (r.rtl) (r.left + r.width) * scale - layout.advance else r.left * scale
            layout.draw(g, x, r.baseline * scale)
        }
        g.dispose()
        File("build/font-preview").mkdirs()
        ImageIO.write(img, "png", File("build/font-preview/$name.png"))
    }
}
