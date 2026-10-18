package app.bestnote.ai.vision.figeval

import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.figlib.FigSpec
import app.bestnote.engine.layout.figlib.FigureLibrary
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * FIG-LIB evaluation boards: a whiteboard photo made up from ground-truth figures. Each figure is
 * drawn as a teacher would (marker strokes that wobble, overshoot and vary in width, dashes by hand,
 * handwritten labels in Latin, Hebrew or Arabic fonts), at a random size and tilt, then the board is
 * lit unevenly, given glare and noise, and seen in perspective. The ground truth is each figure's
 * spec and its box in the final photo (0–1000).
 */
class BoardSynth(private val seed: Int) {
    private val rnd = Random(seed)

    class Placed(val spec: FigSpec, val box: List<Int>)

    private val fonts: Map<String, Font> by lazy {
        fun load(name: String) = File(root(), "core/designsystem/src/main/res/font/$name").takeIf { it.exists() }?.let { Font.createFont(Font.TRUETYPE_FONT, it) }
        mapOf(
            "latin" to (load("caveat.ttf") ?: Font(Font.SANS_SERIF, Font.PLAIN, 10)),
            "hebrew" to (load("gveret_levin.ttf") ?: Font(Font.SANS_SERIF, Font.PLAIN, 10)),
            "arabic" to (load("noto_naskh_arabic.ttf") ?: Font(Font.SANS_SERIF, Font.PLAIN, 10)),
        )
    }

    private fun root(): File {
        var f = File("").absoluteFile
        while (!File(f, "settings.gradle.kts").exists()) f = f.parentFile
        return f
    }

    /** Draws [specs] (up to six) on one board; writes [out] (JPEG) and returns where each figure is. */
    fun board(specs: List<FigSpec>, out: File, title: String? = null): List<Placed> {
        val w = 1600
        val h = 1100
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(236, 237, 233)
        g.fillRect(0, 0, w, h)
        val cols = 3
        val rows = 2
        val top = if (title != null) 90 else 20
        val cw = (w - 40) / cols
        val ch = (h - top - 20) / rows
        if (title != null) text(g, title, 60.0, 60.0, 46f, Color(30, 30, 40), rnd.nextDouble(-0.02, 0.02))
        val ink = if (rnd.nextBoolean()) Color(28, 30, 38) else Color(30, 55, 150)
        val boxes = ArrayList<Pair<FigSpec, DoubleArray>>()
        for ((i, s) in specs.take(cols * rows).withIndex()) {
            val d = FigureLibrary.render(s)?.diagram ?: continue
            val cx = 20 + (i % cols) * cw
            val cy = top + (i / cols) * ch
            val fill = rnd.nextDouble(0.62, 0.86)
            val k = min(cw * fill / d.viewBox.width, ch * fill / d.viewBox.height)
            val rot = rnd.nextDouble(-0.07, 0.07)
            val ox = cx + cw / 2.0 + rnd.nextDouble(-0.05, 0.05) * cw
            val oy = cy + ch / 2.0 + rnd.nextDouble(-0.05, 0.05) * ch
            val vc = d.viewBox.center
            fun map(p: Vec2): Pair<Double, Double> {
                val x = (p.x - vc.x) * k
                val y = (p.y - vc.y) * k
                return Pair(ox + x * cos(rot) - y * sin(rot), oy + x * sin(rot) + y * cos(rot))
            }
            val marker = (k * 1.35).coerceIn(2.6, 5.0).toFloat()
            val bb = doubleArrayOf(1e9, 1e9, -1e9, -1e9)
            fun grow(p: Pair<Double, Double>) { bb[0] = min(bb[0], p.first); bb[1] = min(bb[1], p.second); bb[2] = max(bb[2], p.first); bb[3] = max(bb[3], p.second) }
            drawFigure(g, d, ::map, k, marker, ink, rot) { grow(it) }
            boxes += s to bb
        }
        g.dispose()
        // Lighting, glare and noise, then the camera's perspective.
        val lit = light(img)
        val (warped, hmap) = perspective(lit)
        out.parentFile.mkdirs()
        javax.imageio.ImageIO.write(warped, "jpg", out)
        return boxes.map { (s, b) ->
            val corners = listOf(b[0] to b[1], b[2] to b[1], b[2] to b[3], b[0] to b[3]).map { hmap(it.first, it.second) }
            val l = corners.minOf { it.first } / w * 1000
            val t = corners.minOf { it.second } / h * 1000
            val r = corners.maxOf { it.first } / w * 1000
            val bt = corners.maxOf { it.second } / h * 1000
            Placed(s, listOf(l, t, r, bt).map { it.roundToInt().coerceIn(0, 1000) })
        }
    }

    private fun colourFor(argb: Long, ink: Color): Color = when (argb or 0xFF000000L) {
        NotebookPalette.EMPHASIS -> if (ink.blue > 100) Color(180, 40, 40) else Color(30, 60, 170)
        NotebookPalette.ESSENTIAL -> Color(185, 40, 45)
        NotebookPalette.ANNOTATION -> Color(30, 120, 60)
        else -> ink
    }

    private fun drawFigure(g: Graphics2D, d: VectorDiagram, map: (Vec2) -> Pair<Double, Double>, k: Double, width: Float, ink: Color, rot: Double, grow: (Pair<Double, Double>) -> Unit) {
        for (s in d.shapes) {
            val c = colourFor(s.style.colorArgb, ink)
            when (s) {
                is VectorShape.Line -> stroke(g, listOf(map(s.from), map(s.to)), c, width, s.style.dashed, grow)
                is VectorShape.Arrow -> {
                    val a = map(s.from)
                    val b = map(s.to)
                    stroke(g, listOf(a, b), c, width, s.style.dashed, grow)
                    val ang = atan2(a.second - b.second, a.first - b.first)
                    val len = (s.headSize * k).coerceIn(10.0, 22.0)
                    for (sp in listOf(-0.42, 0.42)) {
                        val q = sp + rnd.nextDouble(-0.08, 0.08)
                        stroke(g, listOf(b, Pair(b.first + len * cos(ang + q), b.second + len * sin(ang + q))), c, width, false, grow)
                    }
                }
                is VectorShape.Polyline -> stroke(g, (if (s.closed) s.points + s.points.first() else s.points).map(map), c, width, s.style.dashed, grow)
                is VectorShape.Ellipse -> {
                    val b = s.bounds
                    val n = 60
                    val start = rnd.nextDouble(0.0, 2 * PI)
                    val pts = (0..n + 2).map { i -> val t = start + i * 2 * PI / n; map(Vec2(b.center.x + b.width / 2 * cos(t).toFloat(), b.center.y + b.height / 2 * sin(t).toFloat())) }
                    stroke(g, pts, c, width, s.style.dashed, grow)
                }
                is VectorShape.Label -> {
                    val p = map(s.at)
                    grow(Pair(p.first - 10, p.second - 10)); grow(Pair(p.first + 10, p.second + 10))
                    label(g, s.text, p.first, p.second, (12 * k * 1.1).toFloat().coerceIn(18f, 46f), c, rot)
                }
                else -> Unit
            }
        }
    }

    /** One marker stroke through [pts]: wobble, overshoot, uneven width; dashed by hand when asked. */
    private fun stroke(g: Graphics2D, pts0: List<Pair<Double, Double>>, c: Color, width: Float, dashed: Boolean, grow: (Pair<Double, Double>) -> Unit) {
        if (pts0.size < 2) return
        val pts = resample(pts0, 3.0)
        val len = pts.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
        val a1 = rnd.nextDouble(0.4, 1.4)
        val l1 = rnd.nextDouble(60.0, 160.0)
        val p1 = rnd.nextDouble(0.0, 6.28)
        val a2 = rnd.nextDouble(0.2, 0.6)
        val p2 = rnd.nextDouble(0.0, 6.28)
        var s = 0.0
        val out = ArrayList<Pair<Double, Double>>()
        for (i in pts.indices) {
            if (i > 0) s += hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
            val a = pts[max(0, i - 1)]
            val b = pts[min(pts.size - 1, i + 1)]
            val tx = b.first - a.first
            val ty = b.second - a.second
            val tl = max(1e-6, hypot(tx, ty))
            val off = a1 * sin(2 * PI * s / l1 + p1) + a2 * sin(2 * PI * s / 23.0 + p2)
            out += Pair(pts[i].first - ty / tl * off, pts[i].second + tx / tl * off)
        }
        // A little overshoot at the start and end, as a marker does.
        if (len > 30 && !dashed) {
            val o = rnd.nextDouble(0.0, 4.0)
            val (ax, ay) = out[0]
            val (bx, by) = out[1]
            val dl = max(1e-6, hypot(ax - bx, ay - by))
            out.add(0, Pair(ax + (ax - bx) / dl * o, ay + (ay - by) / dl * o))
        }
        g.color = Color(c.red, c.green, c.blue, rnd.nextInt(200, 250))
        g.stroke = BasicStroke(width * rnd.nextDouble(0.85, 1.15).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        if (dashed) {
            var acc = 0.0
            var on = true
            var run = ArrayList<Pair<Double, Double>>()
            for (i in 1 until out.size) {
                acc += hypot(out[i].first - out[i - 1].first, out[i].second - out[i - 1].second)
                if (on) run += out[i - 1]
                if (acc > (if (on) 14.0 else 9.0)) {
                    if (on) { run += out[i]; path(g, run); run = ArrayList() }
                    on = !on
                    acc = 0.0
                }
            }
            if (on && run.size > 1) path(g, run)
        } else {
            path(g, out)
        }
        out.forEach(grow)
    }

    private fun path(g: Graphics2D, p: List<Pair<Double, Double>>) {
        if (p.size < 2) return
        val path = Path2D.Double()
        path.moveTo(p[0].first, p[0].second)
        for (q in p.drop(1)) path.lineTo(q.first, q.second)
        g.draw(path)
    }

    private fun resample(p: List<Pair<Double, Double>>, step: Double): List<Pair<Double, Double>> {
        val out = arrayListOf(p[0])
        for ((a, b) in p.zipWithNext()) {
            val n = max(1, (hypot(b.first - a.first, b.second - a.second) / step).toInt())
            for (i in 1..n) out += Pair(a.first + (b.first - a.first) * i / n, a.second + (b.second - a.second) * i / n)
        }
        return out
    }

    private val greek = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "theta" to "θ", "lambda" to "λ", "mu" to "μ", "varepsilon" to "ε", "epsilon" to "ε",
        "Delta" to "Δ", "omega" to "ω", "pi" to "π", "circ" to "°", "sigma" to "σ", "phi" to "φ",
    )

    /** A label as handwriting: subscripts smaller and lower, \vec as an arrow over the letter. */
    private fun label(g: Graphics2D, tex: String, cx: Double, cy: Double, size: Float, c: Color, rot: Double) {
        val parts = ArrayList<Triple<String, Int, Boolean>>() // text, 0 base / -1 sub / 1 sup, vec
        var i = 0
        var t = tex.replace(Regex("""\\vec\s*"""), "\u0000").replace("^{\\circ}", "°").replace("^\\circ", "°").replace("\\text", "").replace("\\,", " ")
        t = Regex("""\\([a-zA-Z]+)""").replace(t) { m -> greek[m.groupValues[1]] ?: m.groupValues[1] }
        var vec = false
        while (i < t.length) {
            val ch = t[i]
            when {
                ch == '\u0000' -> { vec = true; i++ }
                (ch == '_' || ch == '^') && i + 1 < t.length -> {
                    val (body, next) = if (t[i + 1] == '{') t.substring(i + 2).substringBefore('}') to (i + 2 + t.substring(i + 2).substringBefore('}').length + 1) else t[i + 1].toString() to i + 2
                    parts += Triple(body, if (ch == '_') -1 else 1, false)
                    i = next
                }
                ch == '{' || ch == '}' -> i++
                else -> { parts += Triple(ch.toString(), 0, vec); vec = false; i++ }
            }
        }
        val fontOf = { s: String ->
            when {
                s.any { it in '֐'..'׿' } -> fonts.getValue("hebrew")
                s.any { it in '؀'..'ۿ' } -> fonts.getValue("arabic")
                fonts.getValue("latin").canDisplayUpTo(s) == -1 -> fonts.getValue("latin")
                else -> Font(Font.SERIF, Font.ITALIC, 10) // Greek letters
            }
        }
        val widths = parts.map { (s, lvl, _) -> g.getFontMetrics(fontOf(s).deriveFont(if (lvl == 0) size else size * 0.65f)).stringWidth(s) }
        val total = widths.sum()
        val old = g.transform
        g.translate(cx, cy)
        g.rotate(rot + rnd.nextDouble(-0.05, 0.05))
        var x = -total / 2.0
        g.color = c
        for ((j, part) in parts.withIndex()) {
            val (s, lvl, v) = part
            val f = fontOf(s).deriveFont(if (lvl == 0) size else size * 0.65f)
            g.font = f
            val y = size * 0.35 + (if (lvl < 0) size * 0.3 else if (lvl > 0) -size * 0.45 else 0.0)
            g.drawString(s, x.toFloat(), y.toFloat())
            if (v) {
                g.stroke = BasicStroke(2f)
                val ay = y - size * 0.75
                g.drawLine(x.toInt(), ay.toInt(), (x + widths[j]).toInt(), ay.toInt())
                g.drawLine((x + widths[j]).toInt(), ay.toInt(), (x + widths[j] - 5).toInt(), (ay - 3).toInt())
            }
            x += widths[j]
        }
        g.transform = old
    }

    private fun text(g: Graphics2D, s: String, x: Double, y: Double, size: Float, c: Color, rot: Double) {
        val f = when { s.any { it in '֐'..'׿' } -> fonts.getValue("hebrew"); s.any { it in '؀'..'ۿ' } -> fonts.getValue("arabic"); else -> fonts.getValue("latin") }
        val old = g.transform
        g.translate(x, y)
        g.rotate(rot)
        g.font = f.deriveFont(size)
        g.color = c
        g.drawString(s, 0f, 0f)
        g.transform = old
    }

    private fun light(src: BufferedImage): BufferedImage {
        val w = src.width
        val h = src.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val gx = rnd.nextDouble(0.2, 0.8) * w
        val gy = rnd.nextDouble(0.1, 0.5) * h
        val gr = rnd.nextDouble(0.15, 0.3) * w
        val dark = rnd.nextDouble(0.75, 0.92)
        val tint = doubleArrayOf(rnd.nextDouble(0.95, 1.03), rnd.nextDouble(0.95, 1.02), rnd.nextDouble(0.93, 1.02))
        for (y in 0 until h) for (x in 0 until w) {
            val p = src.getRGB(x, y)
            // Light falls off towards one side and the corners; a soft glare spot.
            val fall = dark + (1 - dark) * (1 - (x.toDouble() / w) * 0.6 - ((y - h / 2.0) / h).let { it * it } * 0.8).coerceIn(0.0, 1.0)
            val glare = 38 * kotlin.math.exp(-((x - gx) * (x - gx) + (y - gy) * (y - gy)) / (2 * gr * gr))
            val n = rnd.nextDouble(-6.0, 6.0)
            val ch = intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255).mapIndexed { i, v -> (v * fall * tint[i] + glare + n).roundToInt().coerceIn(0, 255) }
            out.setRGB(x, y, (ch[0] shl 16) or (ch[1] shl 8) or ch[2])
        }
        return out
    }

    /** A mild camera perspective; returns the photo and the board → photo point map. */
    private fun perspective(src: BufferedImage): Pair<BufferedImage, (Double, Double) -> Pair<Double, Double>> {
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val j = { rnd.nextDouble(0.0, 0.045) }
        // Where the board's corners land in the photo.
        val dst = listOf(Pair(w * j(), h * j()), Pair(w * (1 - j()), h * j()), Pair(w * (1 - j()), h * (1 - j())), Pair(w * j(), h * (1 - j())))
        val srcC = listOf(Pair(0.0, 0.0), Pair(w, 0.0), Pair(w, h), Pair(0.0, h))
        val fwd = homography(srcC, dst)
        val inv = homography(dst, srcC)
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val bg = Color(70, 72, 70).rgb
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val (sx, sy) = apply(inv, x.toDouble(), y.toDouble())
            val ix = sx.roundToInt()
            val iy = sy.roundToInt()
            out.setRGB(x, y, if (ix in 0 until src.width && iy in 0 until src.height) src.getRGB(ix, iy) else bg)
        }
        return out to { x, y -> apply(fwd, x, y) }
    }

    private fun apply(m: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val z = m[6] * x + m[7] * y + 1
        return Pair((m[0] * x + m[1] * y + m[2]) / z, (m[3] * x + m[4] * y + m[5]) / z)
    }

    /** The 3×3 homography (h33 = 1) taking [s] to [d] (four point pairs). */
    private fun homography(s: List<Pair<Double, Double>>, d: List<Pair<Double, Double>>): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val (x, y) = s[i]
            val (u, v) = d[i]
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            val p = (c until 8).maxBy { kotlin.math.abs(a[it][c]) }
            val tmp = a[c]; a[c] = a[p]; a[p] = tmp
            for (r in 0 until 8) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c until 9) a[r][k] -= f * a[c][k]
            }
        }
        return DoubleArray(8) { a[it][8] / a[it][it] }
    }
}
