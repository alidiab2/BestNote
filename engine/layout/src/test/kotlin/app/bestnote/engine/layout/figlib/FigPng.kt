package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A quick AWT contact sheet of library drawings (labels as plain text), for looking at them while working. */
object FigPng {
    fun sheet(items: List<Pair<String, VectorDiagram>>, file: File, cell: Int = 360, cols: Int = 4) {
        val rows = (items.size + cols - 1) / cols
        val img = BufferedImage(cols * cell, rows * cell, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        for ((i, item) in items.withIndex()) {
            val (name, d) = item
            val ox = (i % cols) * cell
            val oy = (i / cols) * cell
            g.color = Color(220, 220, 220)
            g.drawRect(ox, oy, cell - 1, cell - 1)
            g.color = Color.GRAY
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            g.drawString(name, ox + 4, oy + 14)
            val vb = d.viewBox
            val k = min((cell - 30f) / vb.width, (cell - 30f) / vb.height)
            fun x(v: Float) = ox + 15 + (v - vb.left) * k
            fun y(v: Float) = oy + 20 + (v - vb.top) * k
            for (s in d.shapes) {
                g.color = Color((s.style.colorArgb and 0xFFFFFF).toInt())
                g.stroke = if (s.style.dashed) BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(6f, 5f), 0f) else BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                when (s) {
                    is VectorShape.Line -> g.draw(java.awt.geom.Line2D.Float(x(s.from.x), y(s.from.y), x(s.to.x), y(s.to.y)))
                    is VectorShape.Arrow -> {
                        g.draw(java.awt.geom.Line2D.Float(x(s.from.x), y(s.from.y), x(s.to.x), y(s.to.y)))
                        val a = atan2(y(s.from.y) - y(s.to.y), x(s.from.x) - x(s.to.x))
                        val len = s.headSize * k
                        for (sp in listOf(-0.45f, 0.45f)) g.draw(java.awt.geom.Line2D.Float(x(s.to.x), y(s.to.y), x(s.to.x) + len * cos(a + sp), y(s.to.y) + len * sin(a + sp)))
                    }
                    is VectorShape.Polyline -> {
                        val p = Path2D.Float()
                        s.points.forEachIndexed { j, q -> if (j == 0) p.moveTo(x(q.x), y(q.y)) else p.lineTo(x(q.x), y(q.y)) }
                        if (s.closed) p.closePath()
                        g.draw(p)
                    }
                    is VectorShape.Ellipse -> g.draw(java.awt.geom.Ellipse2D.Float(x(s.bounds.left), y(s.bounds.top), s.bounds.width * k, s.bounds.height * k))
                    is VectorShape.Label -> {
                        val t = s.text.replace("^\\circ", "°").replace("\\", "").replace("{", "").replace("}", "")
                        g.font = Font(Font.SERIF, Font.ITALIC, (Pen.FONT * k).toInt().coerceAtLeast(8))
                        val fm = g.fontMetrics
                        g.drawString(t, x(s.at.x) - fm.stringWidth(t) / 2f, y(s.at.y) + (fm.ascent - fm.descent) / 2f)
                    }
                    else -> Unit
                }
            }
        }
        g.dispose()
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
    }
}
