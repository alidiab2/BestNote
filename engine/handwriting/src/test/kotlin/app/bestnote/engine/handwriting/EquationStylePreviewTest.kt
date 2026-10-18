package app.bestnote.engine.handwriting

import app.bestnote.engine.mathtype.GlyphPath
import app.bestnote.engine.mathtype.MathFont
import app.bestnote.engine.mathtype.MathOp
import app.bestnote.engine.mathtype.MathTypesetter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** MATH-02: the same formula in both equation styles, written to build/handwriting-preview/equation-styles.png for eyeballing. */
class EquationStylePreviewTest {
    private val tex = "W_{nc} = \\boxed{\\Delta E_T},\\ \\cancel{W_N} + \\underbrace{W_{mg} + W_F}_{\\text{work}} = d\\vec r \\cdot \\hat x"

    private fun path(p: GlyphPath): Path2D.Float {
        val out = Path2D.Float()
        var k = 0
        val c = p.coords
        for (op in p.ops) when (op) {
            GlyphPath.MOVE -> { out.moveTo(c[k].toFloat(), c[k + 1].toFloat()); k += 2 }
            GlyphPath.LINE -> { out.lineTo(c[k].toFloat(), c[k + 1].toFloat()); k += 2 }
            GlyphPath.QUAD -> { out.quadTo(c[k].toFloat(), c[k + 1].toFloat(), c[k + 2].toFloat(), c[k + 3].toFloat()); k += 4 }
            GlyphPath.CUBIC -> { out.curveTo(c[k].toFloat(), c[k + 1].toFloat(), c[k + 2].toFloat(), c[k + 3].toFloat(), c[k + 4].toFloat(), c[k + 5].toFloat()); k += 6 }
            else -> out.closePath()
        }
        return out
    }

    @Test fun sameFormulaInBothStyles() {
        val size = 40f
        val scale = 2f
        val l = MathTypesetter(MathFont.stixTwo).typeset(tex, size)
        val ink = HandMath.write(tex, size)!!
        assertTrue(l.ops.isNotEmpty() && ink.strokes.isNotEmpty())
        val w = (maxOf(l.width, ink.width) + 40) * scale
        val img = BufferedImage(w.toInt(), (2 * 150 * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color(0x1E2A5A)
        g.font = Font("SansSerif", Font.PLAIN, 12)
        g.drawString("Typeset (LaTeX)", 8, 14)
        g.drawString("Handwritten", 8, (150 * scale).toInt() + 14)
        // Typeset
        val bx = 20f * scale
        val by = 95f * scale
        for (op in l.ops) when (op) {
            is MathOp.GlyphOp -> {
                val t = AffineTransform().apply { translate((bx + op.x * scale).toDouble(), (by + op.y * scale).toDouble()); scale((op.scale * scale).toDouble(), -(op.scale * scale).toDouble()) }
                g.fill(t.createTransformedShape(path(op.glyph.path)))
            }
            is MathOp.RuleOp -> g.fill(java.awt.geom.Rectangle2D.Float(bx + op.x * scale, by + op.top * scale, op.width * scale, op.height * scale))
            is MathOp.LineOp -> { g.stroke = BasicStroke(op.thickness * scale); g.draw(Line2D.Float(bx + op.x1 * scale, by + op.y1 * scale, bx + op.x2 * scale, by + op.y2 * scale)) }
            is MathOp.TextOp -> { g.font = Font("SansSerif", Font.PLAIN, (op.sizePt * scale).toInt()); g.drawString(op.text, bx + op.x * scale, by + op.y * scale) }
            else -> Unit
        }
        // Handwritten
        val hy = (150 * scale) + 95f * scale
        g.stroke = BasicStroke(ink.thickness * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (s in ink.strokes) for (i in 0 until s.size / 2 - 1) {
            g.draw(Line2D.Float(bx + s[2 * i] * scale, hy + s[2 * i + 1] * scale, bx + s[2 * i + 2] * scale, hy + s[2 * i + 3] * scale))
        }
        g.dispose()
        File("build/handwriting-preview").mkdirs()
        ImageIO.write(img, "png", File("build/handwriting-preview/equation-styles.png"))
    }
}
