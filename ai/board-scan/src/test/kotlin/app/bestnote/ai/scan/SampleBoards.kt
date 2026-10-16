package app.bestnote.ai.scan

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO

/**
 * Sample board photos rendered in code (so tests need no binary fixtures): a board drawn in
 * perspective on a textured background, with marker writing, a shadow and a glare spot.
 * [truth] is the board's outline in normalised coordinates.
 */
internal class SampleBoard(val name: String, val image: BufferedImage, val truth: Quad) {
    val raster: Raster
        get() = Raster(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))

    fun save(dir: File = File("build/scan-samples")) {
        dir.mkdirs()
        ImageIO.write(image, "jpg", File(dir, "$name.jpg"))
    }

    companion object {
        private const val W = 1600
        private const val H = 1200

        fun all() = listOf(whiteboardDarkWall(), paperOnTable(), blackboardLightWall(), whiteboardGreyWall())

        fun whiteboardDarkWall() = render(
            "whiteboard-dark-wall",
            Quad(Pt(0.14f, 0.18f), Pt(0.9f, 0.1f), Pt(0.84f, 0.86f), Pt(0.1f, 0.8f)),
            background = Color(0x4A4F57), noise = 18, board = Color(0xF1F2EE), frame = Color(0x9DA3AA),
            ink = listOf(Color(0x1D3C8A), Color(0xD32F2F), Color(0x2E7D32)), shadow = true, glare = true,
        )

        fun paperOnTable() = render(
            "paper-on-table",
            Quad(Pt(0.3f, 0.12f), Pt(0.78f, 0.2f), Pt(0.68f, 0.92f), Pt(0.2f, 0.82f)),
            background = Color(0x8B5A2B), noise = 30, board = Color(0xFAF8F2), frame = null,
            ink = listOf(Color(0x202020), Color(0x1D3C8A)), shadow = true, glare = false,
        )

        fun blackboardLightWall() = render(
            "blackboard-light-wall",
            Quad(Pt(0.08f, 0.15f), Pt(0.93f, 0.2f), Pt(0.9f, 0.85f), Pt(0.06f, 0.88f)),
            background = Color(0xD9D4C7), noise = 12, board = Color(0x22362D), frame = Color(0x6B4A2E),
            ink = listOf(Color(0xF4F4F0), Color(0xF6E27A)), shadow = false, glare = false,
        )

        fun whiteboardGreyWall() = render(
            "whiteboard-grey-wall",
            Quad(Pt(0.12f, 0.12f), Pt(0.88f, 0.15f), Pt(0.87f, 0.84f), Pt(0.13f, 0.87f)),
            background = Color(0xBFC2C4), noise = 10, board = Color(0xF4F5F2), frame = Color(0x55595E),
            ink = listOf(Color(0x1D3C8A), Color(0xD32F2F)), shadow = true, glare = true,
        )

        @Suppress("LongParameterList")
        private fun render(
            name: String,
            truth: Quad,
            background: Color,
            noise: Int,
            board: Color,
            frame: Color?,
            ink: List<Color>,
            shadow: Boolean,
            glare: Boolean,
        ): SampleBoard {
            val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = background
            g.fillRect(0, 0, W, H)
            val rnd = Random(name.hashCode().toLong())
            // Texture: grain and streaks.
            for (i in 0 until 9000) {
                val d = rnd.nextInt(2 * noise + 1) - noise
                g.color = Color((background.red + d).coerceIn(0, 255), (background.green + d).coerceIn(0, 255), (background.blue + d).coerceIn(0, 255))
                g.fillRect(rnd.nextInt(W), rnd.nextInt(H), 2 + rnd.nextInt(30), 2 + rnd.nextInt(3))
            }
            val outline = Path2D.Float().apply {
                val c = truth.corners
                moveTo(c[0].x * W, c[0].y * H)
                for (p in c.drop(1)) lineTo(p.x * W, p.y * H)
                closePath()
            }
            g.color = board
            g.fill(outline)
            frame?.let {
                g.color = it
                g.stroke = BasicStroke(14f)
                g.draw(outline)
            }
            // Writing, placed through the board's perspective so it sits on the board.
            g.clip = outline
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 64)
            val lines = listOf("Integration by parts", "∫ u dv = uv − ∫ v du", "x² + y² = r²", "F = m a  →  a = F / m", "d = 3, a_n = a_1 + (n-1)d")
            for ((i, text) in lines.withIndex()) {
                val at = Perspective.mapBack(truth, 0.08f, 0.16f + i * 0.17f)
                g.color = ink[i % ink.size]
                val save = g.transform
                g.transform = AffineTransform.getTranslateInstance(at.x * W.toDouble(), at.y * H.toDouble())
                g.drawString(text, 0, 0)
                g.transform = save
            }
            g.stroke = BasicStroke(6f)
            g.color = ink[0]
            val a = Perspective.mapBack(truth, 0.6f, 0.75f)
            val b = Perspective.mapBack(truth, 0.9f, 0.9f)
            g.drawRect((a.x * W).toInt(), (a.y * H).toInt(), ((b.x - a.x) * W).toInt(), ((b.y - a.y) * H).toInt())
            g.clip = null
            if (shadow) {
                g.paint = GradientPaint(0f, 0f, Color(0, 0, 0, 0), W * 0.9f, H.toFloat(), Color(0, 0, 0, 110))
                g.fillRect(0, 0, W, H)
            }
            if (glare) {
                g.paint = RadialGradientPaint(W * 0.62f, H * 0.32f, W * 0.14f, floatArrayOf(0f, 1f), arrayOf(Color(255, 255, 255, 170), Color(255, 255, 255, 0)))
                g.fillRect(0, 0, W, H)
            }
            g.dispose()
            return SampleBoard(name, img, truth)
        }
    }
}
