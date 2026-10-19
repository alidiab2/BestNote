package app.bestnote.ai.scan.bench

import app.bestnote.ai.scan.Enhance
import app.bestnote.ai.scan.Perspective
import app.bestnote.ai.scan.Pt
import app.bestnote.ai.scan.Quad
import app.bestnote.ai.scan.Raster
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * REQ-001 figure bench: the owner's boards (alidiab2/BestNote-requests#2) as the app sees them.
 * board2 is a raw phone photo: cropped to the board, flattened (longest side 1600) and auto-enhanced like
 * BoardScan.flatten. board3/6/8 are the in-app photo viewer's screenshots of already flattened boards,
 * cropped to the board.
 */
object BenchBoards {
    val names = listOf("board2", "board3", "board6", "board8")

    private val cache = HashMap<String, Raster>()

    fun raster(img: BufferedImage): Raster {
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Raster(img.width, img.height, px)
    }

    fun image(r: Raster): BufferedImage = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, r.width, r.height, r.argb, 0, r.width) }

    private fun load(name: String): Raster = raster(ImageIO.read(BenchBoards::class.java.getResource("/bench/$name.jpg")))

    fun board(name: String): Raster = cache.getOrPut(name) {
        if (name != "board2") return@getOrPut load(name)
        val src = load("raw-board2")
        // The detector misses this board (a student in front of it): the corners as the owner would drag them.
        val q = Quad(Pt(0f, 0.248f), Pt(0.872f, 0.207f), Pt(0.9f, 0.688f), Pt(0f, 0.731f))
        val (w, h) = Perspective.outputSize(q, src.width, src.height, 1600)
        Enhance.apply(Perspective.warp(src, q, w, h))
    }

    /** Boards stacked top to bottom (same width): one photo for one AI call, fewer calls. */
    private val stacks = HashMap<List<String>, Raster>()

    fun stack(vararg names: String): Raster = synchronized(stacks) { stacks.getOrPut(names.toList()) { stacked(*names) } }

    private fun stacked(vararg names: String): Raster {
        val parts = names.map(::board)
        val w = parts.maxOf { it.width }
        val scaled = parts.map { if (it.width == w) it else raster(scale(image(it), w, it.height * w / it.width)) }
        val h = scaled.sumOf { it.height }
        val out = IntArray(w * h) { -1 }
        var y0 = 0
        for (p in scaled) {
            for (y in 0 until p.height) System.arraycopy(p.argb, y * p.width, out, (y0 + y) * w, p.width)
            y0 += p.height
        }
        return Raster(w, h, out)
    }

    /** Where each stacked board starts (y, in stacked pixels) and its height. */
    fun stackOffsets(vararg names: String): Map<String, Pair<Int, Int>> {
        val parts = names.map(::board)
        val w = parts.maxOf { it.width }
        var y = 0
        return names.zip(parts).associate { (n, p) -> val h = p.height * w / p.width; (n to (y to h)).also { y += h } }
    }

    private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { out ->
        out.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
    }

    private val halves = java.util.IdentityHashMap<Raster, Raster>()

    /** [r] at half size, area-averaged like a camera image scaled down. */
    fun half(r: Raster): Raster = synchronized(halves) {
        halves.getOrPut(r) {
            val src = image(r)
            val w = r.width / 2
            val h = r.height / 2
            val scaled = src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING)
            raster(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(scaled, 0, 0, null); dispose() } })
        }
    }

    fun write(r: Raster, file: File) {
        file.parentFile.mkdirs()
        ImageIO.write(image(r), if (file.name.endsWith(".jpg")) "jpg" else "png", file)
    }
}
