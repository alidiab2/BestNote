package app.bestnote.engine.layout

import app.bestnote.core.model.InkSemantic
import app.bestnote.core.model.RectPt
import app.bestnote.core.model.StrokeStyle
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape

/**
 * AI-12: what the teacher DREW on the board. The model says where a drawing is ([FigureBox]), what it
 * is ([FigureKind]) and what its labels say and where ([FigureLabel]). The app REDRAWS it from scratch:
 * the photo's pixels are only geometry input (strokes found in the crop → straight lines, arcs, arrows
 * and dashed lines fitted, snapped parallel/perpendicular, labels typeset at their places), drawn as
 * clean shapes in the note's ink. Simple drawings may come from the AI's data ([FigureShape],
 * [FigureGraph], table rows); a cleaned crop ([FigureRaster]) is only the flagged last resort.
 */
enum class FigureKind {
    Graph, Diagram, Table, Shape, Arrow, Drawing;

    companion object {
        /** Forgiving: "plot", "chart", "function graph" → [Graph]; unknown → [Drawing]. */
        fun of(raw: String?): FigureKind {
            val s = raw?.lowercase()?.filter(Char::isLetter) ?: return Drawing
            return when {
                s.isEmpty() -> Drawing
                "graph" in s || "plot" in s || "chart" in s || "axes" in s || "function" in s -> Graph
                "table" in s || "grid" in s -> Table
                "arrow" in s || "connector" in s -> Arrow
                "shape" in s || "geometr" in s || s in GEOMETRIC -> Shape
                "diagram" in s || "flow" in s || "freebody" in s || "fbd" in s || "circuit" in s -> Diagram
                else -> Drawing
            }
        }

        private val GEOMETRIC = setOf("circle", "triangle", "rectangle", "square", "polygon", "ellipse", "line")
    }
}

/**
 * Where a drawing is in the board photo the AI read, as fractions of the photo's width and height
 * (0..1, top-left origin). [of] reads the wire box (`[left, top, right, bottom]`, 0–1000) and also
 * accepts 0–1 boxes and swapped corners.
 */
data class FigureBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** The same box grown by [fraction] of its size on every side, kept inside the photo. */
    fun padded(fraction: Float): FigureBox {
        val dx = width * fraction
        val dy = height * fraction
        return FigureBox((left - dx).coerceAtLeast(0f), (top - dy).coerceAtLeast(0f), (right + dx).coerceAtMost(1f), (bottom + dy).coerceAtMost(1f))
    }

    companion object {
        /** Smallest side kept (a sliver is a model slip, not a drawing). */
        const val MIN_SPAN = 0.015f

        /** The wire box → a clean box, or null when it is unusable (wrong count, not finite, degenerate). */
        fun of(values: List<Float>): FigureBox? {
            if (values.size != 4 || values.any { !it.isFinite() || it < 0f }) return null
            val max = values.max()
            // The prompt asks for 0–1000; a model that answers in fractions is understood too.
            val scale = if (max <= 1.0001f) 1f else 1000f
            val s = values.map { (it / scale).coerceIn(0f, 1f) }
            val l = minOf(s[0], s[2])
            val r = maxOf(s[0], s[2])
            val t = minOf(s[1], s[3])
            val b = maxOf(s[1], s[3])
            if (r - l < MIN_SPAN || b - t < MIN_SPAN) return null
            return FigureBox(l, t, r, b)
        }
    }
}

/** A simple function graph: axis ranges, axis labels, and the curve as an expression in x ([expr]) or [points] (data units). */
data class FigureGraph(
    val xMin: Float = -5f,
    val xMax: Float = 5f,
    val yMin: Float? = null,
    val yMax: Float? = null,
    val xLabel: String = "x",
    val yLabel: String = "y",
    val expr: String? = null,
    val points: List<Vec2> = emptyList(),
)

/** A basic geometric shape: [type] and its points, as fractions (0..1) of the photo like [FigureBox]. */
data class FigureShape(val type: Type, val points: List<Vec2>) {
    enum class Type { Line, Arrow, Rect, Circle, Ellipse, Triangle, Polygon }
}

/** A label of a drawing ("N", "f_k", "37^\\circ"): LaTeX [text] centred at [at] (photo fractions like [FigureBox]; null = unknown). */
data class FigureLabel(val text: String, val at: Vec2? = null)

/** The drawing as a cleaned picture (transparent background, ink recoloured), stored as the notebook image [asset]: the last resort. */
data class FigureRaster(val asset: String, val width: Int, val height: Int)

/** How the app drew a figure (shown in review; a [Picture] is flagged to the user). */
enum class FigureSource {
    /** Not resolved yet (straight from the AI). */
    Pending,

    /** Redrawn from scratch: primitives fitted to the strokes in the photo, snapped and labelled. */
    Redrawn,

    /** Drawn from the AI's structured data (simple shape, graph or table). */
    FromData,

    /** Fitting failed: a cleaned crop of the photo. */
    Picture,
}

/**
 * A drawing on the board. [box] is where it is in the photo the AI read (null: the AI gave no usable
 * box, then only [graph]/[shape] data can draw it). [ref]: on a follow-up photo, the index of an
 * already drawn figure of the page that the model kept (its drawing is re-attached). Once the app has
 * read the photo, the element's [NoteBlock.diagram] holds the clean redraw ([source] = Redrawn or
 * FromData), or [raster] holds a cleaned crop ([source] = Picture).
 */
data class BoardFigure(
    val kind: FigureKind,
    val box: FigureBox? = null,
    val labels: List<FigureLabel> = emptyList(),
    val graph: FigureGraph? = null,
    val shape: FigureShape? = null,
    val raster: FigureRaster? = null,
    val source: FigureSource = FigureSource.Pending,
    val ref: Int? = null,
    /** FIG-LIB: what the drawing is as a library element and its parameters ([app.bestnote.engine.layout.figlib.FigureLibrary]); null = not recognised. */
    val spec: app.bestnote.engine.layout.figlib.FigSpec? = null,
)

/** Crisp vector drawings for simple figures given as data (used only when they match the photo, or no photo crop exists). */
object FigureVectors {
    /**
     * [shape] (photo fractions) inside [box] → a diagram in the box's own units (0..box size × 1000),
     * so its proportions match the board.
     */
    fun shape(shape: FigureShape, box: FigureBox): VectorDiagram? {
        if (shape.points.isEmpty()) return null
        val w = (box.width * SCALE).coerceAtLeast(1f)
        val h = (box.height * SCALE).coerceAtLeast(1f)
        val pts = shape.points.map { Vec2(((it.x - box.left) * SCALE).coerceIn(0f, w), ((it.y - box.top) * SCALE).coerceIn(0f, h)) }
        val s: VectorShape = when (shape.type) {
            FigureShape.Type.Line -> if (pts.size < 2) return null else VectorShape.Line(pts[0], pts[1])
            FigureShape.Type.Arrow -> if (pts.size < 2) return null else VectorShape.Arrow(pts[0], pts[1])
            FigureShape.Type.Rect -> if (pts.size < 2) return null else VectorShape.Rect(bounds(pts))
            FigureShape.Type.Circle, FigureShape.Type.Ellipse -> if (pts.size < 2) return null else VectorShape.Ellipse(bounds(pts))
            FigureShape.Type.Triangle, FigureShape.Type.Polygon -> if (pts.size < 3) return null else VectorShape.Polyline(pts, closed = true)
        }
        return VectorDiagram(RectPt(0f, 0f, w, h), listOf(s))
    }

    /** A function graph: axes with arrows and labels, a few ticks, and the curve sampled from [FigureGraph.expr] or its points. */
    fun graph(g: FigureGraph): VectorDiagram? {
        val xMin = minOf(g.xMin, g.xMax)
        val xMax = maxOf(g.xMin, g.xMax).let { if (it - xMin < 1e-3f) xMin + 1f else it }
        val curve: List<Vec2> = when {
            g.points.size >= 2 -> g.points.sortedBy { it.x }
            g.expr != null -> {
                val f = FigureMath.parse(g.expr) ?: return null
                (0..SAMPLES).mapNotNull { i ->
                    val x = xMin + (xMax - xMin) * i / SAMPLES
                    f(x.toDouble()).takeIf { it.isFinite() }?.let { Vec2(x, it.toFloat()) }
                }
            }
            else -> emptyList()
        }
        if (curve.size < 2) return null
        val ys = curve.map { it.y }
        val yMin = g.yMin ?: minOf(0f, ys.min())
        val yMax = (g.yMax ?: maxOf(0f, ys.max())).let { if (it - yMin < 1e-3f) yMin + 1f else it }
        val w = 220f
        val h = 160f
        val pad = 18f
        fun px(x: Float) = pad + (x - xMin) / (xMax - xMin) * (w - 2 * pad)
        fun py(y: Float) = h - pad - (y - yMin) / (yMax - yMin) * (h - 2 * pad)
        val ox = px(0f.coerceIn(xMin, xMax))
        val oy = py(0f.coerceIn(yMin, yMax))
        val shapes = arrayListOf<VectorShape>(
            VectorShape.Arrow(Vec2(pad - 6f, oy), Vec2(w - 4f, oy)),
            VectorShape.Arrow(Vec2(ox, h - pad + 6f), Vec2(ox, 4f)),
            VectorShape.Label(Vec2(w - 14f, oy + 14f), g.xLabel, latex = true),
            VectorShape.Label(Vec2(ox + 5f, 14f), g.yLabel, latex = true),
        )
        // The curve, cut where it leaves the plotted range (asymptotes).
        var run = ArrayList<Vec2>()
        val ink = StrokeStyle(colorArgb = NotebookPalette.ink(InkSemantic.Emphasis))
        for (p in curve) {
            if (p.y < yMin - (yMax - yMin) * 0.05f || p.y > yMax + (yMax - yMin) * 0.05f) {
                if (run.size >= 2) shapes += VectorShape.Polyline(run, style = ink)
                run = ArrayList()
            } else {
                run += Vec2(px(p.x), py(p.y))
            }
        }
        if (run.size >= 2) shapes += VectorShape.Polyline(run, style = ink)
        return VectorDiagram(RectPt(0f, 0f, w, h), shapes)
    }

    private fun bounds(p: List<Vec2>) = RectPt(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })

    private const val SCALE = 1000f
    private const val SAMPLES = 120
}

/**
 * A tiny, safe expression reader for graph curves ("x^2-1", "1/x", "2sin(x)", "e^(-x)", "ln x"): numbers,
 * x, pi, e, + − × ÷ ^, parentheses, implicit multiplication and the usual functions. Anything else → null.
 */
object FigureMath {
    fun parse(text: String): ((Double) -> Double)? = runCatching {
        val p = Parser(clean(text))
        val f = p.expr()
        if (p.pos != p.s.length) null else f
    }.getOrNull()

    private fun clean(t: String): String = t.lowercase()
        .substringAfter("y=").substringAfter("f(x)=")
        .replace("\\left", "").replace("\\right", "").replace("\\cdot", "*").replace("\\times", "*")
        .replace("\\frac{", "frac{").replace("\\sqrt", "sqrt").replace("\\ln", "ln").replace("\\log", "log")
        .replace("\\sin", "sin").replace("\\cos", "cos").replace("\\tan", "tan").replace("\\exp", "exp").replace("\\pi", "pi")
        .replace('{', '(').replace('}', ')').replace("−", "-").replace("·", "*").replace("×", "*").replace("÷", "/")
        .filter { !it.isWhitespace() }

    private class Parser(val s: String) {
        var pos = 0

        fun expr(): (Double) -> Double {
            var f = term()
            while (pos < s.length && (s[pos] == '+' || s[pos] == '-')) {
                val op = s[pos++]
                val a = f
                val b = term()
                f = if (op == '+') { x -> a(x) + b(x) } else { x -> a(x) - b(x) }
            }
            return f
        }

        fun term(): (Double) -> Double {
            var f = unary()
            while (pos < s.length) {
                val c = s[pos]
                val a = f
                f = when {
                    c == '*' -> { pos++; val b = unary(); { x -> a(x) * b(x) } }
                    c == '/' -> { pos++; val b = unary(); { x -> a(x) / b(x) } }
                    c.isLetterOrDigit() || c == '(' || c == '.' -> { val b = power(); { x -> a(x) * b(x) } } // implicit ×
                    else -> return f
                }
            }
            return f
        }

        fun unary(): (Double) -> Double = if (pos < s.length && s[pos] == '-') { pos++; val f = unary(); { x -> -f(x) } } else power()

        fun power(): (Double) -> Double {
            val base = atom()
            if (pos < s.length && s[pos] == '^') {
                pos++
                val e = unary()
                return { x -> Math.pow(base(x), e(x)) }
            }
            return base
        }

        fun atom(): (Double) -> Double {
            require(pos < s.length)
            val c = s[pos]
            if (c == '(') {
                pos++
                val f = expr()
                require(pos < s.length && s[pos] == ')')
                pos++
                return f
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                val v = s.substring(start, pos).toDouble()
                return { v }
            }
            for ((name, fn) in FUNCS) if (s.startsWith(name, pos)) {
                pos += name.length
                if (name == "frac") {
                    val a = atom()
                    val b = atom()
                    return { x -> a(x) / b(x) }
                }
                val arg = power()
                return { x -> fn(arg(x)) }
            }
            if (s.startsWith("pi", pos)) { pos += 2; return { Math.PI } }
            if (c == 'x') { pos++; return { x -> x } }
            if (c == 'e') { pos++; return { Math.E } }
            throw IllegalArgumentException("unexpected $c")
        }

        companion object {
            val FUNCS: List<Pair<String, (Double) -> Double>> = listOf(
                "frac" to { v -> v }, "sqrt" to Math::sqrt, "sin" to Math::sin, "cos" to Math::cos, "tan" to Math::tan,
                "exp" to Math::exp, "ln" to Math::log, "log" to Math::log10, "abs" to Math::abs,
            )
        }
    }
}
