package app.bestnote.ai.scan.bench

import app.bestnote.ai.scan.Raster
import app.bestnote.ai.scan.figure.FigureRedraw
import app.bestnote.ai.scan.figure.InkMask
import app.bestnote.ai.scan.figure.Skeleton
import app.bestnote.ai.vision.Canary
import app.bestnote.ai.vision.NoteWire
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.BoardFigure
import app.bestnote.engine.layout.FigureBox
import app.bestnote.engine.layout.FigureKind
import app.bestnote.engine.layout.FigureLabel
import app.bestnote.engine.layout.FigureSource
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * REQ-001 figure bench: every drawing on the owner's boards, run through a figure pipeline and scored
 * against the photo. Ground truth = where each drawing is and what its labels say (hand-annotated,
 * board pixels). Scores per drawing:
 *  - recall: share of the photo's ink centre lines (labels' writing left out) within [TOL] of the redraw;
 *  - precision: share of the redraw's strokes within [TOL] of the photo's ink;
 *  - f1 of the two (a Chamfer-style match, the redraw aligned on the photo);
 *  - labels: ground-truth labels found among the drawing's typeset labels (text only), and extra ones.
 * Pictures (photo | redraw) go to build/bench/<run>/.
 */
object FigureBench {
    /** A drawing on a board: its box and its labels (board pixels). */
    class Truth(val id: String, val board: String, val box: IntArray, val labels: List<Pair<String, IntArray>>)

    private fun t(id: String, board: String, box: IntArray, vararg labels: Pair<String, IntArray>) = Truth(id, board, box, labels.toList())
    private fun p(x: Int, y: Int) = intArrayOf(x, y)

    val truths = listOf(
        t("graph-U", "board2", intArrayOf(928, 278, 1278, 502), "U(x)" to p(1003, 293), "30" to p(938, 328), "2" to p(1055, 410), "5" to p(1167, 446), "7" to p(1242, 434), "x" to p(1264, 442)),
        t("graph-F", "board3", intArrayOf(160, 30, 625, 465), "F\\,(N)" to p(245, 40), "F" to p(170, 165), "\\Delta t" to p(455, 437), "\\Delta t" to p(595, 450)),
        t("block-vF", "board6", intArrayOf(80, 28, 392, 145), "v" to p(210, 42), "m" to p(212, 113), "F" to p(355, 103)),
        t("block-v", "board6", intArrayOf(600, 150, 905, 252), "v" to p(740, 170), "m" to p(727, 226)),
        t("pair-F", "board8", intArrayOf(493, 105, 768, 172), "F_{2,1}" to p(515, 128), "m_1" to p(600, 148), "m_2" to p(645, 150), "F_{1,2}" to p(740, 132)),
        t("before", "board8", intArrayOf(1100, 72, 1470, 182), "\\vec{v}_1" to p(1193, 92), "\\vec{v}_2" to p(1323, 94), "m_1" to p(1195, 152), "m_2" to p(1335, 152)),
        t("after", "board8", intArrayOf(1110, 278, 1430, 410), "\\vec{u}_1" to p(1227, 295), "\\vec{u}_2" to p(1318, 298), "m_1" to p(1235, 345), "m_2" to p(1330, 350)),
    )

    /** The two photos sent to the AI (recorded answers in resources/bench). */
    val stacks = mapOf(
        "live-claude-stack-2-8" to listOf("board2", "board8"),
        "live-claude-stack-3-6" to listOf("board3", "board6"),
        "live-sonnet-stack-2-8" to listOf("board2", "board8"),
    )

    /** Claude Opus 5.5 (the app's default) answered both photos; Sonnet 5.5 (the owner's pick) the first. */
    val opus = listOf("live-claude-stack-2-8", "live-claude-stack-3-6")
    val sonnet = listOf("live-sonnet-stack-2-8")

    class Score(val id: String, val source: String, val recall: Float, val precision: Float, val labelsFound: Int, val labelsTotal: Int, val extraLabels: Int, val fineF1: Float = 0f, val kinks: Int = 0, val placed: Int = 0) {
        val f1: Float get() = if (recall + precision == 0f) 0f else 2 * recall * precision / (recall + precision)
        override fun toString() = "%-9s %-9s f1=%.2f fine=%.2f kinks=%d recall=%.2f precision=%.2f labels=%d/%d placed=%d extra=%d".format(id, source, f1, fineF1, kinks, recall, precision, labelsFound, labelsTotal, placed, extraLabels)
    }

    /** One drawing as a pipeline drew it: the diagram and where it sits on its board (board pixels; null = not on the photo, e.g. a library drawing). */
    class Drawn(val diagram: VectorDiagram?, val source: FigureSource, val at: FloatArray?)

    // ------------------------------------------------------------------ pipelines

    /** The redraw from the photo with the ground truth's box and labels (the CV alone, a perfect model). */
    fun oracle(truth: Truth, resolve: (NoteDocument, Raster) -> NoteDocument = { d, r -> FigureRedraw.resolve(d, r) }): Drawn {
        val b = BenchBoards.board(truth.board)
        fun fx(x: Int) = x / b.width.toFloat()
        fun fy(y: Int) = y / b.height.toFloat()
        val f = BoardFigure(FigureKind.Diagram, FigureBox(fx(truth.box[0]), fy(truth.box[1]), fx(truth.box[2]), fy(truth.box[3])), truth.labels.map { (s, at) -> FigureLabel(s, Vec2(fx(at[0]), fy(at[1]))) })
        val out = resolve(NoteDocument(listOf(NoteBlock(NoteElement.Diagram, figure = f))), b).flatten().single()
        return drawn(out, b.width.toFloat(), b.height.toFloat(), 0f, 1f)
    }

    /** The app's path on a recorded answer: [answer] → figures resolved on the stacked photo; each ground-truth drawing gets the answer's drawing that overlaps it most. */
    fun live(answer: String, resolve: (NoteDocument, Raster, Float) -> NoteDocument = { d, r, k -> FigureRedraw.resolve(d, r, k) }, edit: (NoteDocument) -> NoteDocument = { it }): Map<String, Drawn?> {
        val boards = stacks.getValue(answer)
        val photo = BenchBoards.stack(*boards.toTypedArray())
        val yScale = (photo.height + Canary.stripHeight(photo.width, photo.height)).toFloat() / photo.height
        val doc = edit(NoteWire.answer(javaClass.getResource("/bench/$answer.json")!!.readText()))
        val figures = resolve(doc, photo, yScale).flatten().filter { it.element == NoteElement.Diagram }
        val offsets = BenchBoards.stackOffsets(*boards.toTypedArray())
        return truths.filter { it.board in boards }.associate { truth ->
            val (y0, _) = offsets.getValue(truth.board)
            val k = photo.width / BenchBoards.board(truth.board).width.toFloat()
            // Stack fractions → board pixels.
            fun onBoard(box: FigureBox) = floatArrayOf(box.left * photo.width / k, (box.top * photo.height - y0) / k, box.right * photo.width / k, (box.bottom * photo.height - y0) / k)
            val gt = truth.box.map { it.toFloat() }.toFloatArray()
            val best = figures.mapNotNull { f -> f.figure?.box?.let { f to iou(onBoard(it), gt) } }.maxByOrNull { it.second }?.takeIf { it.second > 0f }?.first
            truth.id to best?.let { drawn(it, photo.width.toFloat(), photo.height.toFloat(), y0.toFloat(), k) }
        }
    }

    private fun drawn(b: NoteBlock, w: Float, h: Float, y0: Float, k: Float): Drawn {
        val f = b.figure
        val src = f?.source ?: FigureSource.Pending
        val at = if (src == FigureSource.Redrawn) f?.box?.let { floatArrayOf(it.left * w / k, (it.top * h - y0) / k, it.right * w / k, (it.bottom * h - y0) / k) } else null
        return Drawn(b.diagram, src, at)
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0]))
        val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
        val inter = ix * iy
        val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (u <= 0f) 0f else inter / u
    }

    // ------------------------------------------------------------------ scoring

    /** Scores [d] against [truth]; writes photo | redraw to build/bench/[run]/[truth].png. */
    fun score(truth: Truth, d: Drawn?, run: String): Score {
        val b = BenchBoards.board(truth.board)
        val pad = 6
        val l = (truth.box[0] - pad).coerceAtLeast(0)
        val tp = (truth.box[1] - pad).coerceAtLeast(0)
        val r = (truth.box[2] + pad).coerceAtMost(b.width)
        val bt = (truth.box[3] + pad).coerceAtMost(b.height)
        val w = r - l
        val h = bt - tp
        val crop = Raster(w, h, IntArray(w * h) { i -> b[l + i % w, tp + i / w] })
        val mask0 = InkMask.of(crop)
        // The labels' writing is not drawing: about a letter around each label, as wide as its text.
        val letter = 0.075f * hypot(w.toFloat(), h.toFloat()).coerceAtMost(260f) + 4f
        val boxes = truth.labels.map { (s, at) ->
            val hw = letter * (0.35f * chars(s) + 0.3f)
            floatArrayOf(at[0] - l - hw, at[1] - tp - letter * 0.75f, at[0] - l + hw, at[1] - tp + letter * 0.75f)
        }
        fun inLabel(x: Int, y: Int) = boxes.any { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] }
        val ink = BooleanArray(w * h) { i -> mask0.ink[i] && !inLabel(i % w, i / w) }
        val mask = InkMask(w, h, ink)
        val skel = Skeleton.thin(mask)
        val ref = skel.indices.filter { skel[it] }
        // The redraw in the crop's pixels.
        val drawn = BooleanArray(w * h)
        val texts = ArrayList<String>()
        val textAt = ArrayList<Pair<String, Vec2>>()
        val mapped = d?.diagram?.let { place(it, d.at, mask, l.toFloat(), tp.toFloat()) }
        mapped?.let { (diagram, map) ->
            for (s in diagram.shapes) {
                if (s is VectorShape.Label) { texts += s.text; textAt += norm(s.text) to map(s.at); continue }
                for ((a, c) in segments(s)) rasterLine(map(a), map(c), drawn, w, h)
            }
        }
        val tol = TOL * hypot((truth.box[2] - truth.box[0]).toFloat(), (truth.box[3] - truth.box[1]).toFloat())
        val toDrawn = distance(drawn, w, h)
        val toInk = distance(mask0.ink, w, h)
        val recall = if (ref.isEmpty()) 0f else ref.count { toDrawn[it] <= tol }.toFloat() / ref.size
        val ours = drawn.indices.filter { drawn[it] && !inLabel(it % w, it / w) }
        val precision = if (ours.isEmpty()) 0f else ours.count { toInk[it] <= tol }.toFloat() / ours.size
        // The same at a third of the tolerance (about half a stroke): how exactly the lines follow the teacher's.
        val fine = tol / 3
        val fr = if (ref.isEmpty()) 0f else ref.count { toDrawn[it] <= fine }.toFloat() / ref.size
        val fp = if (ours.isEmpty()) 0f else ours.count { toInk[it] <= fine }.toFloat() / ours.size
        val fineF1 = if (fr + fp == 0f) 0f else 2 * fr * fp / (fr + fp)
        val kinks = mapped?.let { (diagram, map) -> kinks(diagram, map, skel, w, h, tol * 1.2f) } ?: 0
        // Placed: typeset within about a letter of where the teacher wrote it (redraws only: a library drawing has its own layout).
        val near = 0.06f * hypot(w.toFloat(), h.toFloat())
        val free = textAt.toMutableList()
        var placed = 0
        if (d?.at != null) for ((s, at) in truth.labels) {
            val hit = free.firstOrNull { (t, p) -> t == norm(s) && hypot(p.x - (at[0] - l), p.y - (at[1] - tp)) <= near } ?: continue
            free.remove(hit)
            placed++
        }
        val want = truth.labels.map { norm(it.first) }.toMutableList()
        var found = 0
        var extra = 0
        for (t in texts.map(::norm)) if (want.remove(t)) found++ else extra++
        picture(crop, mask, drawn, texts, mapped, File("build/bench/$run/${truth.id}.png"))
        return Score(truth.id, d?.source?.name ?: "missing", recall, precision, found, truth.labels.size, extra, fineF1, kinks, placed)
    }

    /**
     * Corners the redraw has where the teacher's line runs smooth (a curve drawn as straight pieces): a
     * vertex turning more than 25° whose surrounding photo centre line (within [r]) is one smooth stroke
     * (fits a line or a gentle arc closely), not two strokes meeting.
     */
    private fun kinks(d: VectorDiagram, map: (Vec2) -> Vec2, skel: BooleanArray, w: Int, h: Int, r: Float): Int {
        val vertices = ArrayList<Pair<Vec2, Float>>()
        fun turn(a: Vec2, b: Vec2, c: Vec2): Float {
            val u = atan2(b.y - a.y, b.x - a.x)
            val v = atan2(c.y - b.y, c.x - b.x)
            var x = Math.toDegrees((v - u).toDouble()).toFloat()
            while (x > 180) x -= 360
            while (x < -180) x += 360
            return kotlin.math.abs(x)
        }
        val segs = ArrayList<Pair<Vec2, Vec2>>()
        for (s in d.shapes) when (s) {
            is VectorShape.Polyline -> {
                val p = s.points.map(map)
                for (i in 1 until p.size - 1) vertices += p[i] to turn(p[i - 1], p[i], p[i + 1])
            }
            is VectorShape.Line -> segs += map(s.from) to map(s.to)
            else -> Unit
        }
        for (i in segs.indices) for (j in segs.indices) {
            if (i >= j) continue
            val (a, b) = segs[i]
            val (c, e) = segs[j]
            fun near(p: Vec2, q: Vec2) = hypot(p.x - q.x, p.y - q.y) < 1.5f
            when {
                near(b, c) -> vertices += b to turn(a, b, e)
                near(a, e) -> vertices += a to turn(c, a, b)
                near(a, c) -> vertices += a to turn(b, a, e)
                near(b, e) -> vertices += b to turn(a, b, c)
            }
        }
        var n = 0
        for ((v, t) in vertices) {
            if (t < 25f) continue
            val pts = ArrayList<Vec2>()
            val x0 = (v.x - r).toInt().coerceAtLeast(0); val x1 = (v.x + r).toInt().coerceAtMost(w - 1)
            val y0 = (v.y - r).toInt().coerceAtLeast(0); val y1 = (v.y + r).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) if (skel[y * w + x] && hypot(x - v.x, y - v.y) <= r) pts += Vec2(x.toFloat(), y.toFloat())
            if (pts.size < 6) continue
            // One smooth stroke: a straight line or a gentle arc through the neighbourhood (a quadratic along its main direction).
            val mx = pts.map { it.x }.average(); val my = pts.map { it.y }.average()
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0
            for (p in pts) { val dx = p.x - mx; val dy = p.y - my; sxx += dx * dx; syy += dy * dy; sxy += dx * dy }
            val th = 0.5 * atan2(2 * sxy, sxx - syy)
            val ux = cos(th); val uy = sin(th)
            val uv = pts.map { p -> val dx = p.x - mx; val dy = p.y - my; (dx * ux + dy * uy) to (-dx * uy + dy * ux) }
            // Least squares v = a + b u + c u².
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var t0 = 0.0; var t1 = 0.0; var t2 = 0.0
            for ((u, vv) in uv) { val u2 = u * u; s0 += 1; s1 += u; s2 += u2; s3 += u2 * u; s4 += u2 * u2; t0 += vv; t1 += vv * u; t2 += vv * u2 }
            val m = arrayOf(doubleArrayOf(s0, s1, s2, t0), doubleArrayOf(s1, s2, s3, t1), doubleArrayOf(s2, s3, s4, t2))
            for (c in 0..2) { val piv = (c..2).maxBy { kotlin.math.abs(m[it][c]) }; val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp; if (kotlin.math.abs(m[c][c]) < 1e-9) continue; for (rr in 0..2) if (rr != c) { val f = m[rr][c] / m[c][c]; for (k in c..3) m[rr][k] -= f * m[c][k] } }
            val a = m[0][3] / m[0][0]; val b = m[1][3] / m[1][1]; val c = m[2][3] / m[2][2]
            val rms = kotlin.math.sqrt(uv.sumOf { (u, vv) -> val e = vv - (a + b * u + c * u * u); e * e } / uv.size)
            if (rms < 0.12 * r) n++
        }
        return n
    }

    /** Where [d] goes in the crop: a redraw at its place on the photo; a drawing not from the photo stretched onto the drawing's ink. */
    private fun place(d: VectorDiagram, at: FloatArray?, ink: InkMask, l: Float, t: Float): Pair<VectorDiagram, (Vec2) -> Vec2> {
        val vb = d.viewBox
        val target = if (at != null) floatArrayOf(at[0] - l, at[1] - t, at[2] - l, at[3] - t) else inkBox(ink)
        val kx = (target[2] - target[0]) / vb.width.coerceAtLeast(1e-3f)
        val ky = (target[3] - target[1]) / vb.height.coerceAtLeast(1e-3f)
        return d to { p: Vec2 -> Vec2(target[0] + (p.x - vb.left) * kx, target[1] + (p.y - vb.top) * ky) }
    }

    private fun inkBox(m: InkMask): FloatArray {
        var x0 = m.width; var y0 = m.height; var x1 = 0; var y1 = 0
        for (i in m.ink.indices) if (m.ink[i]) { val x = i % m.width; val y = i / m.width; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y) }
        return floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
    }

    /** The strokes [s] draws, as straight pieces (arrowheads included). */
    fun segments(s: VectorShape): List<Pair<Vec2, Vec2>> = when (s) {
        is VectorShape.Line -> listOf(s.from to s.to)
        is VectorShape.Arrow -> {
            val back = atan2(s.from.y - s.to.y, s.from.x - s.to.x)
            listOf(s.from to s.to) + listOf(-0.45f, 0.45f).map { a -> s.to to Vec2(s.to.x + s.headSize * cos(back + a), s.to.y + s.headSize * sin(back + a)) }
        }
        is VectorShape.Polyline -> (if (s.closed && s.points.isNotEmpty()) s.points + s.points.first() else s.points).zipWithNext()
        is VectorShape.Rect -> s.rect.let { r -> listOf(Vec2(r.left, r.top), Vec2(r.right, r.top), Vec2(r.right, r.bottom), Vec2(r.left, r.bottom), Vec2(r.left, r.top)).zipWithNext() }
        is VectorShape.Ellipse -> s.bounds.let { r -> (0..48).map { i -> val a = 2 * Math.PI * i / 48; Vec2((r.left + r.right) / 2 + r.width / 2 * cos(a).toFloat(), (r.top + r.bottom) / 2 + r.height / 2 * sin(a).toFloat()) }.zipWithNext() }
        else -> emptyList()
    }

    private fun rasterLine(a: Vec2, b: Vec2, out: BooleanArray, w: Int, h: Int) {
        val n = max(1, (hypot(b.x - a.x, b.y - a.y) * 2).toInt())
        for (i in 0..n) {
            val x = (a.x + (b.x - a.x) * i / n).roundToInt()
            val y = (a.y + (b.y - a.y) * i / n).roundToInt()
            if (x in 0 until w && y in 0 until h) out[y * w + x] = true
        }
    }

    private fun distance(set: BooleanArray, w: Int, h: Int): FloatArray {
        val d = FloatArray(w * h) { if (set[it]) 0f else 1e9f }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) { v = min(v, d[i - w] + 1f); if (x > 0) v = min(v, d[i - w - 1] + 1.414f); if (x < w - 1) v = min(v, d[i - w + 1] + 1.414f) }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1f)
            if (y < h - 1) { v = min(v, d[i + w] + 1f); if (x < w - 1) v = min(v, d[i + w + 1] + 1.414f); if (x > 0) v = min(v, d[i + w - 1] + 1.414f) }
            d[i] = v
        }
        return d
    }

    /** A label's text, compared loosely: "\vec{v}_1" = "v_1" = "v1", "F\,(N)" = "F(N)". */
    fun norm(s: String): String = s.replace("\\vec", "").replace("\\overrightarrow", "").replace("\\text", "").replace("\\Delta", "Δ").replace("\\,", "")
        .filter { it !in "{}_ \\" }

    private fun chars(s: String) = norm(s).length.coerceAtLeast(1)

    private fun picture(crop: Raster, mask: InkMask, drawn: BooleanArray, texts: List<String>, mapped: Pair<VectorDiagram, (Vec2) -> Vec2>?, file: File) {
        val w = crop.width
        val h = crop.height
        val k = max(1, (420 / max(w, h)) + 1)
        val out = BufferedImage((w * 2 + 8) * k, h * k, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, out.width, out.height)
        g.drawImage(BenchBoards.image(crop), 0, 0, w * k, h * k, null)
        g.translate((w + 8) * k, 0)
        // The photo's ink faintly under the redraw: what should be covered.
        for (i in mask.ink.indices) if (mask.ink[i]) { g.color = Color(225, 225, 232); g.fillRect((i % w) * k, (i / w) * k, k, k) }
        mapped?.let { (d, map) ->
            for (s in d.shapes) {
                g.color = Color(s.style.colorArgb.toInt()).let { if (it.red + it.green + it.blue > 600) Color.BLACK else it }
                g.stroke = if (s.style.dashed) BasicStroke(1.6f * k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(5f * k, 4f * k), 0f) else BasicStroke(1.6f * k, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                if (s is VectorShape.Label) {
                    val p = map(s.at)
                    g.font = g.font.deriveFont(11f * k)
                    g.drawString(norm(s.text), p.x * k - 4 * k, p.y * k + 4 * k)
                    continue
                }
                for ((a, c) in segments(s)) { val p = map(a); val q = map(c); g.draw(Line2D.Float(p.x * k, p.y * k, q.x * k, q.y * k)) }
            }
        }
        g.dispose()
        file.parentFile.mkdirs()
        ImageIO.write(out, "png", file)
    }

    /** Tolerance of the match, as a share of the drawing's diagonal (about a stroke and a half on these boards). */
    const val TOL = 0.025f
}
