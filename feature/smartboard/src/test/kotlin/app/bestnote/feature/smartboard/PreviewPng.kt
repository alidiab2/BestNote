package app.bestnote.feature.smartboard

import app.bestnote.engine.handwriting.WrittenStroke
import app.bestnote.engine.layout.RuledPaper
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders a handwritten page to build/smartboard-preview/[name].png for eyeballing (not an assertion). */
internal object PreviewPng {
    fun write(paper: RuledPaper, strokes: List<WrittenStroke>, name: String, scale: Float = 2f) {
        val img = BufferedImage((paper.width * scale).toInt(), (paper.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        if (paper.showLines) {
            g.color = Color(0xC9D6E8)
            for (i in 0 until paper.lineCount) {
                val y = paper.lineY(i) * scale
                g.draw(Line2D.Float(0f, y, img.width.toFloat(), y))
            }
        }
        g.color = Color(0xE8A0A0)
        g.draw(Line2D.Float(paper.marginX * scale, 0f, paper.marginX * scale, img.height.toFloat()))
        for (s in strokes) {
            g.color = Color(s.stroke.brush.colorArgb, true)
            val pts = s.stroke.samples
            for (i in 0 until pts.size - 1) {
                g.stroke = BasicStroke(s.stroke.brush.size * scale * (0.5f + pts[i].pressure), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Float(pts[i].x * scale, pts[i].y * scale, pts[i + 1].x * scale, pts[i + 1].y * scale))
            }
            if (pts.size == 1) g.fillOval((pts[0].x * scale).toInt() - 2, (pts[0].y * scale).toInt() - 2, 4, 4)
        }
        g.dispose()
        File("build/smartboard-preview").mkdirs()
        ImageIO.write(img, "png", File("build/smartboard-preview/$name.png"))
    }
}
