package app.bestnote.ai.scan.figure

import app.bestnote.ai.scan.Raster
import app.bestnote.core.model.InkSemantic
import app.bestnote.core.model.RectPt
import app.bestnote.core.model.StrokeStyle
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.BoardFigure
import app.bestnote.engine.layout.ColorSemantics
import app.bestnote.engine.layout.FigureBox
import app.bestnote.engine.layout.FigureKind
import app.bestnote.engine.layout.FigureLabel
import app.bestnote.engine.layout.FigureRaster
import app.bestnote.engine.layout.FigureSource
import app.bestnote.engine.layout.FigureVectors
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NotebookPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * AI-12: the teacher's drawing, REDRAWN from scratch. The photo is geometry input only: the figure's
 * box is cropped from the flattened board, its ink found ([InkMask]), thinned to centre lines
 * ([Skeleton]), fitted with clean primitives and squared up ([FigureFit]); the labels the AI read are
 * typeset at their places. The redraw is accepted only when it matches the original (most ink is
 * covered by the redraw and the redraw lies on ink); otherwise the AI's simple data draws it, and a
 * cleaned crop of the photo is the last resort (flagged as [FigureSource.Picture]).
 */
object FigureRedraw {
    /** What [redraw] made of one figure; [diagram] is null when nothing usable was found. */
    class Result(
        val diagram: VectorDiagram?,
        val accepted: Boolean,
        /** Share of the original's centre lines that the redraw covers. */
        val recall: Float,
        /** Share of the redraw that lies on the original's ink. */
        val precision: Float,
        val prims: List<Prim>,
        /** The crop's ink, cleaned (transparent background, recoloured): the fallback picture. */
        val cleaned: Raster?,
    )

    /** A crop of [board] in board pixels plus how its working copy is scaled. */
    class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int, val scale: Float, val inner: FigureBox, val image: Raster)

    /** The working crop of [box] (board fractions), padded, resampled so its long side is [WORK_SIDE]. */
    fun crop(board: Raster, box: FigureBox): Crop {
        val padded = box.padded(PAD)
        val l = floor(padded.left * board.width).toInt().coerceIn(0, board.width - 1)
        val t = floor(padded.top * board.height).toInt().coerceIn(0, board.height - 1)
        val r = (padded.right * board.width).roundToInt().coerceIn(l + 1, board.width)
        val b = (padded.bottom * board.height).roundToInt().coerceIn(t + 1, board.height)
        val scale = WORK_SIDE.toFloat() / max(r - l, b - t)
        val w = max(8, ((r - l) * scale).roundToInt())
        val h = max(8, ((b - t) * scale).roundToInt())
        return Crop(l, t, r, b, scale, box, resample(board, l, t, r, b, w, h))
    }

    /** Redraws the figure at [box] of [board]; [labels] are typeset at their places. */
    fun redraw(board: Raster, box0: FigureBox, labels: List<FigureLabel> = emptyList()): Result {
        // The box should hold the drawing and its labels; a label on its edge means the drawing goes on beyond it (live, board 4: h_B's arrow).
        val box = labels.mapNotNull { it.at }.fold(box0) { b, p ->
            val m = LABEL_MARGIN
            FigureBox(min(b.left, p.x - m).coerceAtLeast(0f), min(b.top, p.y - m).coerceAtLeast(0f), max(b.right, p.x + m).coerceAtMost(1f), max(b.bottom, p.y + m).coerceAtMost(1f))
        }
        val crop = crop(board, box)
        val img = crop.image
        val w = img.width
        val h = img.height
        val diag = hypot(w.toFloat(), h.toFloat())
        fun toCrop(p: Vec2) = XY((p.x * board.width - crop.left) * crop.scale, (p.y * board.height - crop.top) * crop.scale)
        val mask = insideBox(InkMask.of(img), crop, board)
        val cleaned = if (mask.count > 0) cleaned(img, mask, background(img, mask)) else null
        if (mask.count < MIN_INK) return Result(null, false, 0f, 0f, emptyList(), cleaned)
        val skel = Skeleton.thin(mask)
        val sw = Skeleton.strokeWidth(mask, skel)
        val graph = Skeleton.trace(skel, w, h)
        val paths = Skeleton.clean(graph, spur = max(4f, 2.5f * sw))
        val (labelled, words) = snapLabels(mask, labels.mapNotNull { l -> l.at?.let { toCrop(it) to l.text } }, diag)
        val labelAt = labelled.map { it.first }
        val bg = background(img, mask)
        val withColour = paths.map { p -> p.points.toList() to colourOf(img, mask, p.points, bg) }
        val il = (box.left * board.width - crop.left) * crop.scale
        val ir = (box.right * board.width - crop.left) * crop.scale
        val itop = (box.top * board.height - crop.top) * crop.scale
        val ib = (box.bottom * board.height - crop.top) * crop.scale
        // Strokes wholly in the padding (bits of neighbouring writing) are not part of the drawing.
        fun kept(p: Prim) = samplesOf(p, sw, diag).any { it.x in il..ir && it.y in itop..ib } && !(p is Prim.Seg && divider(p, w, h))
        // Drawings stacked in one box (live, board 3: the spring in state A above state B) are fitted one by
        // one, each at its own size: the fitting's tolerances scale with the drawing.
        val fitted = bands(mask, il, ir, paths.map { it.points.toList() }).map { (y0, y1) ->
            fun mine(y: Float) = y in y0..y1
            val fitter = FigureFit(sw, if (y0 <= 0f && y1 >= h.toFloat()) diag else hypot(w.toFloat(), y1 - y0))
            val idx = labelled.indices.filter { mine(labelled[it].first.y) }
            fitter.fit(withColour.filter { (pts, _) -> mine(pts.sumOf { it.y.toDouble() }.toFloat() / pts.size) }, idx.map { labelled[it] }, idx.map { words[it] }) to fitter.unsquared
        }
        val prims = fitted.flatMap { it.first }.filter(::kept)
        if (prims.isEmpty()) return Result(null, false, 0f, 0f, emptyList(), cleaned)
        val zone = max(0.075f * diag, 5f * sw)
        // Only the drawing counts: not the padding, not the written words the labels stand for.
        val pad = 1.5f * sw
        val skip = words.filterNotNull().map { floatArrayOf(it[0] - pad, it[1] - pad, it[2] + pad, it[3] + pad) }
        val (recall, precision) = check(fitted.flatMap { it.second }.filter(::kept), paths, mask, labelAt, zone, sw, diag) { q ->
            q.x !in il..ir || q.y !in itop..ib || skip.any { b -> q.x in b[0]..b[2] && q.y in b[1]..b[3] }
        }
        val accepted = recall >= MIN_RECALL && precision >= MIN_PRECISION
        val shapes = ArrayList<VectorShape>()
        for (p in prims) shapes.addAll(shape(p, sw, diag))
        for ((c, text) in labelled) {
            if (c.x in 0f..w.toFloat() && c.y in 0f..h.toFloat()) shapes += VectorShape.Label(Vec2(c.x, c.y), text, latex = true)
        }
        return Result(VectorDiagram(RectPt(0f, 0f, w.toFloat(), h.toFloat()), shapes), accepted, recall, precision, prims, cleaned)
    }

    /**
     * Every figure of [doc] drawn from [board] (the flattened board the AI read). [yScale]: the AI saw the
     * board with a canary strip below it, so its y fractions are multiplied by this (height with strip /
     * board height). [store] keeps a fallback picture and returns its notebook asset (null: no picture).
     * A table with rows becomes a Table element; a figure without a usable box is drawn from its data.
     */
    fun resolve(doc: NoteDocument, board: Raster?, yScale: Float = 1f, store: (Raster) -> FigureRaster? = { null }): NoteDocument {
        val boardInk by lazy { board?.let { InkMask.of(it) } }
        fun one(b: NoteBlock): NoteBlock {
            if (b.columns.isNotEmpty()) return b.copy(columns = b.columns.map { c -> c.map(::one) })
            val f0 = b.figure ?: return b
            if (b.element != NoteElement.Diagram || f0.source != FigureSource.Pending) return b
            val f = onBoard(f0, yScale).let { permille ->
                val ink = boardInk ?: return@let permille
                val pixels = asPixels(f0, ink.width, ink.height) ?: return@let permille
                if (onInk(pixels, ink) > onInk(permille, ink)) pixels else permille
            }
            if (f.kind == FigureKind.Table && b.rows.isNotEmpty()) return b.copy(element = NoteElement.Table, figure = f.copy(source = FigureSource.FromData))
            val box = f.box
            val result = if (board != null && box != null) runCatching { redraw(board, box, f.labels) }.getOrNull() else null
            if (System.getProperty("figure.debug") != null) println("figure ${b.head}: accepted=${result?.accepted} recall=${result?.recall} precision=${result?.precision}")
            if (result?.accepted == true) return b.copy(diagram = result.diagram, figure = f.copy(source = FigureSource.Redrawn))
            val data = f.shape?.let { s -> box?.let { FigureVectors.shape(s, it) } } ?: f.graph?.let(FigureVectors::graph)
            if (data != null) return b.copy(diagram = data, figure = f.copy(source = FigureSource.FromData))
            val picture = result?.cleaned?.let(store)
            if (picture != null) return b.copy(diagram = null, figure = f.copy(raster = picture, source = FigureSource.Picture))
            // Nothing better: an unchecked redraw beats losing the drawing.
            if (result?.diagram != null) return b.copy(diagram = result.diagram, figure = f.copy(source = FigureSource.Redrawn))
            return b.copy(figure = f)
        }
        return doc.copy(elements = doc.elements.map(::one))
    }

    /**
     * Claude answered the 0–1000 fields in pixels of the image it saw (live, board 4): read as pixels,
     * the figure's numbers as board fractions; null when they cannot be pixels (beyond the board).
     */
    internal fun asPixels(f: BoardFigure, width: Int, height: Int): BoardFigure? {
        val kx = 1000f / width
        val ky = 1000f / height
        val box = f.box ?: return null
        if (box.right * kx > 1f || box.bottom * ky > 1.06f || f.labels.isEmpty()) return null
        fun y(v: Float) = (v * ky).coerceIn(0f, 1f)
        return f.copy(
            box = FigureBox(box.left * kx, y(box.top), box.right * kx, y(box.bottom)),
            labels = f.labels.map { l -> l.copy(at = l.at?.let { Vec2((it.x * kx).coerceIn(0f, 1f), y(it.y)) }) },
            shape = f.shape?.let { s -> s.copy(points = s.points.map { Vec2((it.x * kx).coerceIn(0f, 1f), y(it.y)) }) },
        )
    }

    /** How many of [f]'s labels sit on written ink (within about a letter): which reading of the numbers is right. */
    private fun onInk(f: BoardFigure, ink: InkMask): Int {
        val r = max(3, (0.012f * max(ink.width, ink.height)).toInt())
        return f.labels.count { l ->
            val at = l.at ?: return@count false
            val cx = (at.x * ink.width).toInt()
            val cy = (at.y * ink.height).toInt()
            (-r..r).any { dy -> (-r..r).any { dx -> ink[cx + dx, cy + dy] } }
        }
    }

    /** The AI's fractions (of the image it saw, strip included) → fractions of the board. */
    fun onBoard(f: BoardFigure, yScale: Float): BoardFigure {
        if (yScale == 1f) return f
        fun y(v: Float) = (v * yScale).coerceIn(0f, 1f)
        return f.copy(
            box = f.box?.let { FigureBox(it.left, y(it.top), it.right, y(it.bottom)) },
            labels = f.labels.map { l -> l.copy(at = l.at?.let { Vec2(it.x, y(it.y)) }) },
            shape = f.shape?.let { s -> s.copy(points = s.points.map { Vec2(it.x, y(it.y)) }) },
        )
    }

    // ------------------------------------------------------------------ pieces

    /**
     * The AI's label positions are rough (vision models place boxes loosely): each label moves to the
     * centre of the nearest written word (a group of small ink blobs) that no other label took.
     */
    internal fun snapLabels(mask: InkMask, labels: List<Pair<XY, String>>, diag: Float): Pair<List<Pair<XY, String>>, List<FloatArray?>> {
        val none = labels to List<FloatArray?>(labels.size) { null }
        if (labels.isEmpty()) return none
        val (label, sizes) = mask.components()
        val w = mask.width
        val n = sizes.size
        val box = Array(n) { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -1f, -1f) }
        for (i in label.indices) {
            val c = label[i]
            if (c < 0) continue
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val b = box[c]
            b[0] = min(b[0], x); b[1] = min(b[1], y); b[2] = max(b[2], x); b[3] = max(b[3], y)
        }
        val glyphMax = 0.12f * max(mask.width, mask.height)
        // A frame drawn around a letter (the block "m") is part of the drawing, not of the word.
        fun frame(c: Int): Boolean = (0 until n).any { o ->
            o != c && sizes[o] > 0 && box[o][0] > box[c][0] && box[o][2] < box[c][2] && box[o][1] > box[c][1] && box[o][3] < box[c][3]
        }
        val glyphs = (0 until n).filter { val b = box[it]; b[2] >= 0 && max(b[2] - b[0], b[3] - b[1]) < glyphMax && !frame(it) }
        if (glyphs.isEmpty()) return none
        // Words: glyphs closer than about half a letter (of the taller one), so a row of dashes does not chain into one "word".
        val cap = 0.035f * diag
        val parent = IntArray(glyphs.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        for (i in glyphs.indices) for (j in i + 1 until glyphs.size) {
            val a = box[glyphs[i]]
            val b = box[glyphs[j]]
            val gap = min(cap, 0.5f * max(a[3] - a[1], b[3] - b[1]))
            if (a[0] - gap <= b[2] && b[0] - gap <= a[2] && a[1] - gap <= b[3] && b[1] - gap <= a[3]) parent[find(i)] = find(j)
        }
        val wordBoxes = glyphs.indices.groupBy(::find).values.map { g ->
            floatArrayOf(g.minOf { box[glyphs[it]][0] }, g.minOf { box[glyphs[it]][1] }, g.maxOf { box[glyphs[it]][2] }, g.maxOf { box[glyphs[it]][3] })
        }
        val words = wordBoxes.map { XY((it[0] + it[2]) / 2, (it[1] + it[3]) / 2) }
        val reach = 0.2f * diag
        // A label the AI put on a written word belongs to it; one put on other ink (a letter inside a block,
        // touching it) stays where it is. Only labels placed beside any writing move (greedy, closest first).
        val r = max(3, (0.015f * diag).toInt())
        fun onInk(p: XY) = (-r..r).any { dy -> (-r..r).any { dx -> mask[p.x.toInt() + dx, p.y.toInt() + dy] } }
        fun inWord(p: XY, b: FloatArray) = p.x in b[0] - r..b[2] + r && p.y in b[1] - r..b[3] + r
        val pairs = labels.indices.flatMap { i -> words.indices.map { j -> Triple(i, j, if (inWord(labels[i].first, wordBoxes[j])) 0f else labels[i].first.dist(words[j])) } }
            .filter { (i, _, d) -> d == 0f || (d < reach && !onInk(labels[i].first)) }.sortedBy { it.third }
        val out = labels.toMutableList()
        val matched = MutableList<FloatArray?>(labels.size) { null }
        val doneL = HashSet<Int>()
        val doneW = HashSet<Int>()
        for ((i, j, _) in pairs) {
            if (i in doneL || j in doneW) continue
            out[i] = words[j] to labels[i].second
            matched[i] = wordBoxes[j]
            doneL += i
            doneW += j
        }
        return out to matched
    }

    /** Drops ink that belongs to neighbours: blobs touching the padded crop's edge whose centre lies outside the AI's box, and board dividers crossing the crop. */
    private fun insideBox(mask: InkMask, crop: Crop, board: Raster): InkMask {
        val (label, sizes) = mask.components()
        val w = mask.width
        val h = mask.height
        val n = sizes.size
        val minX = IntArray(n) { Int.MAX_VALUE }; val minY = IntArray(n) { Int.MAX_VALUE }
        val maxX = IntArray(n) { -1 }; val maxY = IntArray(n) { -1 }
        for (i in label.indices) {
            val c = label[i]
            if (c < 0) continue
            val x = i % w
            val y = i / w
            minX[c] = min(minX[c], x); maxX[c] = max(maxX[c], x); minY[c] = min(minY[c], y); maxY[c] = max(maxY[c], y)
        }
        val il = (crop.inner.left * board.width - crop.left) * crop.scale
        val ir = (crop.inner.right * board.width - crop.left) * crop.scale
        val itop = (crop.inner.top * board.height - crop.top) * crop.scale
        val ib = (crop.inner.bottom * board.height - crop.top) * crop.scale
        val drop = BooleanArray(n) { c ->
            val touches = minX[c] <= 1 || minY[c] <= 1 || maxX[c] >= w - 2 || maxY[c] >= h - 2
            val crosses = (minX[c] <= 1 && maxX[c] >= w - 2 && (maxY[c] - minY[c]) < h / 6) || (minY[c] <= 1 && maxY[c] >= h - 2 && (maxX[c] - minX[c]) < w / 6)
            val cx = (minX[c] + maxX[c]) / 2f
            val cy = (minY[c] + maxY[c]) / 2f
            // A board divider: a long thin straight stroke on its own.
            val seam = (maxX[c] - minX[c] < w / 25 && maxY[c] - minY[c] > h * 0.55f) || (maxY[c] - minY[c] < h / 25 && maxX[c] - minX[c] > w * 0.55f)
            crosses || seam || (touches && (cx < il || cx > ir || cy < itop || cy > ib))
        }
        return InkMask(w, h, BooleanArray(label.size) { mask.ink[it] && !drop[label[it]] })
    }

    /** Median colour of the board around the drawing (the non-ink pixels). */
    private fun background(img: Raster, mask: InkMask): Triple<Float, Float, Float> {
        val r = IntArray(256); val g = IntArray(256); val b = IntArray(256)
        var n = 0
        for (i in img.argb.indices) {
            if (mask.ink[i]) continue
            val c = img.argb[i]
            r[c shr 16 and 0xFF]++; g[c shr 8 and 0xFF]++; b[c and 0xFF]++
            n++
        }
        fun med(h: IntArray): Float { var acc = 0; for (k in 0 until 256) { acc += h[k]; if (acc * 2 >= n) return max(1, k).toFloat() }; return 255f }
        return if (n == 0) Triple(255f, 255f, 255f) else Triple(med(r), med(g), med(b))
    }

    /**
     * The colour intent of ink [rgb] on a board of colour [bg]. Photos wash marker colours out, so the
     * ink is compared with the board: how much of each channel it absorbs. Black and grey absorb all
     * channels alike; red absorbs green and blue. The relative colour then goes through [ColorSemantics].
     */
    internal fun semanticOf(r: Float, g: Float, b: Float, bg: Triple<Float, Float, Float>): InkSemantic {
        val ar = (1f - r / bg.first).coerceIn(0f, 1f)
        val ag = (1f - g / bg.second).coerceIn(0f, 1f)
        val ab = (1f - b / bg.third).coerceIn(0f, 1f)
        val top = maxOf(ar, ag, ab)
        if (top < 0.08f || (top - minOf(ar, ag, ab)) / top < COLOUR_SPREAD) return InkSemantic.Regular
        // The ink's own colour: the channels it lets through, stretched to full range.
        val tr = 1f - ar; val tg = 1f - ag; val tb = 1f - ab
        val lo = minOf(tr, tg, tb)
        val hi = maxOf(tr, tg, tb)
        fun ch(v: Float) = ((v - lo) / (hi - lo) * 255f).toInt().coerceIn(0, 255)
        return ColorSemantics.fromBoardColor("#%02x%02x%02x".format(ch(tr), ch(tg), ch(tb)))
    }

    /** The colour intent of a path: the mean of the ink pixels along it, against the board. */
    private fun colourOf(img: Raster, mask: InkMask, pts: List<XY>, bg: Triple<Float, Float, Float>): InkSemantic {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        val step = max(1, pts.size / 60)
        for (k in pts.indices step step) {
            val px = pts[k].x.roundToInt()
            val py = pts[k].y.roundToInt()
            for (dy in -2..2) for (dx in -2..2) {
                if (!mask[px + dx, py + dy]) continue
                val c = img[px + dx, py + dy]
                r += c shr 16 and 0xFF; g += c shr 8 and 0xFF; b += c and 0xFF; n++
            }
        }
        if (n == 0) return InkSemantic.Regular
        return semanticOf(r.toFloat() / n, g.toFloat() / n, b.toFloat() / n, bg)
    }

    /** Coverage both ways, with a tolerance of about a stroke width: (recall, precision). */
    private fun check(prims: List<Prim>, paths: List<Skeleton.Path>, mask: InkMask, labels: List<XY>, zone: Float, sw: Float, diag: Float, ignored: (XY) -> Boolean = { false }): Pair<Float, Float> {
        val w = mask.width
        val h = mask.height
        val tol = max(3f, 1.2f * sw)
        val drawn = BooleanArray(w * h)
        val samples = ArrayList<Pair<XY, Float>>()
        for (p in prims) {
            val pts = samplesOf(p, sw, diag)
            for (q in pts) {
                val x = q.x.roundToInt()
                val y = q.y.roundToInt()
                if (x in 0 until w && y in 0 until h) drawn[y * w + x] = true
            }
            // A spring is redrawn by its band, loop phase free: it is checked by coverage only.
            // A hand-drawn straight line bows a little (as the fit allows): its own tolerance grows with it.
            val near = if (p is Prim.Seg) max(tol, BOW * p.length) else tol
            if (!(p is Prim.Seg && p.dashed) && p !is Prim.Coil) pts.mapTo(samples) { it to near }
        }
        val coils = prims.filterIsInstance<Prim.Coil>()
        fun inCoil(q: XY) = coils.any { c ->
            val d = (c.b - c.a).unit()
            val t = (q.x - c.a.x) * d.x + (q.y - c.a.y) * d.y
            val off = kotlin.math.abs((q.x - c.a.x) * d.y - (q.y - c.a.y) * d.x)
            t in -c.loopWidth..(c.a.dist(c.b) + c.loopWidth) && off <= c.radius * 2.2f + tol
        }
        val toDrawn = distance(drawn, w, h)
        val toInk = distance(mask.ink, w, h)
        var orig = 0
        var covered = 0
        for (p in paths) for (q in p.points) {
            if (ignored(q) || labels.any { it.dist(q) < zone * 0.8f }) continue
            val x = q.x.roundToInt().coerceIn(0, w - 1)
            val y = q.y.roundToInt().coerceIn(0, h - 1)
            orig++
            if (toDrawn[y * w + x] <= tol || inCoil(q)) covered++
        }
        var onInk = 0
        for ((q, near) in samples) {
            val x = q.x.roundToInt().coerceIn(0, w - 1)
            val y = q.y.roundToInt().coerceIn(0, h - 1)
            if (toInk[y * w + x] <= near) onInk++
        }
        val recall = if (orig == 0) 1f else covered.toFloat() / orig
        val precision = if (samples.isEmpty()) 0f else onInk.toFloat() / samples.size
        return recall to precision
    }

    /**
     * Horizontal bands of the crop holding separate drawings: split at nearly empty rows that no stroke
     * crosses; a band with little ink (a label between two drawings) joins its neighbour. One band
     * covering everything when the drawing is in one piece.
     */
    internal fun bands(mask: InkMask, il: Float, ir: Float, paths: List<List<XY>>): List<Pair<Float, Float>> {
        val w = mask.width
        val h = mask.height
        val whole = listOf(0f to h.toFloat())
        val x0 = il.toInt().coerceIn(0, w - 1)
        val x1 = ir.toInt().coerceIn(x0 + 1, w)
        val rows = IntArray(h) { y -> (x0 until x1).count { mask.ink[y * w + it] } }
        val quiet = max(2, ((x1 - x0) * 0.015f).toInt())
        val spans = paths.filter { it.size > 1 }.map { pts -> pts.minOf { it.y } to pts.maxOf { it.y } }
        fun free(y: Int) = rows[y] <= quiet && spans.none { (lo, hi) -> lo < y - 1 && hi > y + 1 }
        val cuts = ArrayList<Int>()
        var y = 0
        while (y < h) {
            if (!free(y)) { y++; continue }
            val start = y
            while (y < h && free(y)) y++
            if (start > 0 && y < h) cuts += (start + y) / 2
        }
        if (cuts.isEmpty()) return whole
        val edges = listOf(0) + cuts + h
        val ink = edges.zipWithNext().map { (a, b) -> (a until b).sumOf { rows[it] } }
        val total = ink.sum().coerceAtLeast(1)
        // Keep only the cuts between two real drawings.
        val kept = ArrayList<Int>()
        var acc = 0
        for (i in cuts.indices) {
            acc += ink[i]
            val rest = ink.drop(i + 1).sum()
            if (acc >= BAND_INK * total && rest >= BAND_INK * total) { kept += cuts[i]; acc = 0 }
        }
        if (kept.isEmpty()) return whole
        return (listOf(0) + kept + h).zipWithNext().map { (a, b) -> a.toFloat() to b.toFloat() }
    }

    /** A board divider or frame crossing the whole crop (top to bottom, or side to side). */
    private fun divider(s: Prim.Seg, w: Int, h: Int): Boolean =
        (minOf(s.a.y, s.b.y) < 0.05f * h && maxOf(s.a.y, s.b.y) > 0.95f * h) || (minOf(s.a.x, s.b.x) < 0.05f * w && maxOf(s.a.x, s.b.x) > 0.95f * w)

    /** Points every pixel along what [p] draws (arrowheads included). */
    private fun samplesOf(p: Prim, sw: Float, diag: Float): List<XY> = when (p) {
        is Prim.Seg -> buildList {
            addAll(line(p.a, p.b))
            if (p.head) for (barb in head(p, sw, diag)) addAll(line(p.b, barb))
            if (p.tail) for (barb in head(p.copy(a = p.b, b = p.a), sw, diag)) addAll(line(p.a, barb))
        }
        is Prim.Coil -> p.points().zipWithNext().flatMap { (a, b) -> line(a, b) }
        is Prim.Arc -> p.points(1f / max(p.r, 1f)).zipWithNext().flatMap { (a, b) -> line(a, b) }
        is Prim.Curve -> p.points.zipWithNext().flatMap { (a, b) -> line(a, b) }
        is Prim.Block -> (p.corners + p.corners[0]).zipWithNext().flatMap { (a, b) -> line(a, b) }
    }

    private fun line(a: XY, b: XY): List<XY> {
        val n = max(1, a.dist(b).toInt())
        return (0..n).map { i -> a + (b - a) * (i.toFloat() / n) }
    }

    /** The two barb ends of an arrowhead at [s]'s tip. */
    fun head(s: Prim.Seg, sw: Float, diag: Float): List<XY> {
        val len = (0.22f * s.length).coerceIn(3f * sw, max(3f * sw, 0.06f * diag))
        val back = kotlin.math.atan2(s.a.y - s.b.y, s.a.x - s.b.x)
        return listOf(-HEAD_SPREAD, HEAD_SPREAD).map { d -> XY(s.b.x + len * cos(back + d), s.b.y + len * sin(back + d)) }
    }

    private fun shape(p: Prim, sw: Float, diag: Float): List<VectorShape> {
        val style = StrokeStyle(colorArgb = NotebookPalette.ink(p.sem), width = sw, dashed = p is Prim.Seg && p.dashed)
        return when (p) {
            is Prim.Seg -> {
                val headSize = (0.22f * p.length).coerceIn(3f * sw, max(3f * sw, 0.06f * diag))
                when {
                    // A dimension arrow: two arrows from the middle out.
                    p.head && p.tail -> ((p.a + p.b) * 0.5f).let { m -> listOf(VectorShape.Arrow(v(m), v(p.b), headSize, style), VectorShape.Arrow(v(m), v(p.a), headSize, style)) }
                    p.head -> listOf(VectorShape.Arrow(v(p.a), v(p.b), headSize = headSize, style = style))
                    else -> listOf(VectorShape.Line(v(p.a), v(p.b), style))
                }
            }
            is Prim.Arc -> listOf(if (p.full) VectorShape.Ellipse(RectPt(p.c.x - p.r, p.c.y - p.r, p.c.x + p.r, p.c.y + p.r), style) else VectorShape.Polyline(p.points().map(::v), style = style))
            is Prim.Curve -> listOf(VectorShape.Polyline(p.points.map(::v), closed = p.closed, style = style))
            is Prim.Coil -> listOf(VectorShape.Polyline(p.points().map(::v), style = style))
            is Prim.Block -> listOf(VectorShape.Polyline(p.corners.map(::v), closed = true, style = style))
        }
    }

    private fun v(p: XY) = Vec2(p.x, p.y)

    /** Two-pass chamfer distance (1, √2) to the nearest set pixel. */
    private fun distance(set: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e9f
        val d = FloatArray(w * h) { if (set[it]) 0f else inf }
        val diagStep = 1.4142f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1f)
            if (y > 0) {
                v = min(v, d[i - w] + 1f)
                if (x > 0) v = min(v, d[i - w - 1] + diagStep)
                if (x < w - 1) v = min(v, d[i - w + 1] + diagStep)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1f)
            if (y < h - 1) {
                v = min(v, d[i + w] + 1f)
                if (x < w - 1) v = min(v, d[i + w + 1] + diagStep)
                if (x > 0) v = min(v, d[i + w - 1] + diagStep)
            }
            d[i] = v
        }
        return d
    }

    /** The crop's ink on a transparent background, each pixel in the notebook ink of its colour intent. */
    fun cleaned(img: Raster, mask: InkMask, bg: Triple<Float, Float, Float>): Raster {
        val out = IntArray(img.width * img.height)
        for (i in out.indices) {
            if (!mask.ink[i]) continue
            val c = img.argb[i]
            val sem = semanticOf((c shr 16 and 0xFF).toFloat(), (c shr 8 and 0xFF).toFloat(), (c and 0xFF).toFloat(), bg)
            out[i] = (NotebookPalette.ink(sem) and 0xFFFFFFL).toInt() or (0xFF shl 24)
        }
        return Raster(img.width, img.height, out)
    }

    /** Bilinear resample of [src]'s rectangle to [w] x [h]. */
    private fun resample(src: Raster, l: Int, t: Int, r: Int, b: Int, w: Int, h: Int): Raster {
        val sw = (r - l).toFloat() / w
        val sh = (b - t).toFloat() / h
        val base = src
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val fy = (t + (y + 0.5f) * sh - 0.5f).coerceIn(0f, (base.height - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, base.height - 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = (l + (x + 0.5f) * sw - 0.5f).coerceIn(0f, (base.width - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, base.width - 1)
                val tx = fx - x0
                val c00 = base[x0, y0]; val c10 = base[x1, y0]; val c01 = base[x0, y1]; val c11 = base[x1, y1]
                fun ch(s: Int): Float {
                    val a = (c00 shr s and 0xFF) * (1 - tx) + (c10 shr s and 0xFF) * tx
                    val bb = (c01 shr s and 0xFF) * (1 - tx) + (c11 shr s and 0xFF) * tx
                    return a * (1 - ty) + bb * ty
                }
                out[y * w + x] = Raster.rgb(ch(16), ch(8), ch(0))
            }
        }
        return Raster(w, h, out)
    }

    /** Padding around the AI's box (its boxes are often a little tight). */
    private const val PAD = 0.08f

    /** Long side of the working crop: strokes a few pixels thick, fast to trace. */
    const val WORK_SIDE = 520
    private const val MIN_INK = 40
    /** How far a straight line may bow from its fit, as a share of its length (FigureFit allows 0.05). */
    private const val BOW = 0.03f
    /** Share of the ink a band needs to be a drawing of its own. */
    private const val BAND_INK = 0.2f
    /** Room kept around each label (board fraction) when the box is grown to hold it. */
    private const val LABEL_MARGIN = 0.04f
    const val MIN_RECALL = 0.75f
    const val MIN_PRECISION = 0.78f
    private val HEAD_SPREAD = (PI / 7).toFloat()
    private const val COLOUR_SPREAD = 0.1f
}
