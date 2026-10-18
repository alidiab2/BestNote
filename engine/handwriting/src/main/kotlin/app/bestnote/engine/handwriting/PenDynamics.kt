package app.bestnote.engine.handwriting

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.LineDash
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One writer: size, slant, how much the hand varies, and the motor parameters that time the pen
 * (a port of tools/handwriting/hw/dynamics.py). Lengths are page units unless named `mm`.
 *
 * @property xh x-height of normal text.
 * @property unitsPerMm page units per millimetre of real handwriting, for the speed model.
 */
internal class Hand(
    val rnd: Rand,
    val xh: Float,
    val unitsPerMm: Float,
    val slantDeg: Float = 0f,
    val jitter: Float = 1f,
    val vmaxMm: Float = 95f,
    val vcurveMm: Float = 32f,
    val accelMm: Float = 1600f,
    val pressure: Float = 0.6f,
    val tiltDeg: Float = 45f,
    val orientationRad: Float = 2.35f,
) {
    private val phase = floatArrayOf(rnd.uniform(0f, 6.28f), rnd.uniform(0f, 6.28f), rnd.uniform(0f, 6.28f))
    val pressureNoise = SmoothNoise(rnd, 900f, 0.06f)
    val speedNoise = SmoothNoise(rnd, 3f * xh, 0.12f)

    fun tilt(tMs: Float): Float {
        val d = tiltDeg + 5f * sin(2 * PI.toFloat() * tMs / 9000f + phase[0]) + 1.8f * sin(2 * PI.toFloat() * tMs / 2300f + phase[1])
        return Math.toRadians(d.coerceIn(35f, 55f).toDouble()).toFloat()
    }

    fun orientation(tMs: Float): Float =
        orientationRad + 0.12f * sin(2 * PI.toFloat() * tMs / 7000f + phase[2]) + 0.04f * sin(2 * PI.toFloat() * tMs / 1700f + 2 * phase[2])
}

/** Air time between strokes: random range plus travel distance at a pen-in-the-air speed. */
internal enum class Gap(val minMs: Float, val maxMs: Float, val travelMmPerS: Float) {
    Letter(50f, 120f, 260f),
    Deferred(90f, 190f, 220f),
    Word(150f, 330f, 260f),
    Line(380f, 720f, 320f),
    Shape(280f, 600f, 240f),
    Ruler(450f, 800f, 200f),
}

/** A finished handwritten stroke: real ink plus when it is written, in natural writing time. */
class WrittenStroke(
    /** Sample times start at 0 for each stroke, like a stroke captured from the pen. */
    val stroke: InkStroke,
    /** When the pen touches down, in ms from the start of the page. */
    val startMs: Long,
    /** Key of the block it belongs to (for tests and replays), null for page furniture. */
    val block: String?,
    /** The row of [block] that drew it (layout QA), -1 = unknown. */
    val part: Int = -1,
) {
    val endMs: Long get() = startMs + stroke.samples.last().timeMillis
}

/** How the strokes are drawn: the user's pen. [size] is in page units. */
data class PenChoice(val penStyle: PenStyle = PenStyle.Fountain, val size: Float = 1.7f, val colorArgb: Int = INK) {
    companion object {
        const val INK = 0xFF1E2433.toInt()
    }
}

/**
 * Collects pen paths in writing order, then times them like a real hand: two-thirds power law for
 * speed (slower on curves), limited acceleration so every stroke starts and ends slowly, a
 * pressure ramp in and out, and air time between strokes that depends on what comes next.
 */
internal class InkBuilder(private val hand: Hand, private val pen: PenChoice) {
    private class Item(
        val pts: Poly, val dot: Boolean, val gap: Gap, val speed: Float, val pressure: Float, val color: Int, val width: Float, val block: String?,
        val shape: ShapeInfo? = null, val dash: LineDash = LineDash.Solid, val part: Int = -1,
    )

    private val items = ArrayList<Item>()
    var block: String? = null
    var part: Int = -1

    fun add(pts: Poly, gap: Gap, color: Int, speed: Float = 1f, pressure: Float = 1f, width: Float = 1f) {
        if (pts.isEmpty()) return
        items += Item(pts, dot = pts.size == 1, gap, speed, pressure, color, width, block, part = part)
    }

    /** AI-12: a clean shape stroke (a redrawn figure): CANVAS-17 [shape] metadata and its [dash]. */
    fun addShape(pts: Poly, color: Int, shape: ShapeInfo, dash: LineDash = LineDash.Solid, width: Float = 1f) {
        if (pts.size < 2) return
        items += Item(pts, dot = false, Gap.Ruler, 0.9f, 1f, color, width, block, shape, dash, part)
    }

    fun dot(at: P, gap: Gap, color: Int, pressure: Float = 1f, width: Float = 1f) {
        items += Item(listOf(at), dot = true, gap, 1f, pressure, color, width, block, part = part)
    }

    val isEmpty: Boolean get() = items.isEmpty()

    /** Ink bounds of everything added for [key] so far: l, t, r, b. */
    fun boundsOf(key: String): FloatArray? = Geom.bounds(items.filter { it.block == key }.map { it.pts })

    fun build(startMs: Float = 200f): List<WrittenStroke> {
        val out = ArrayList<WrittenStroke>(items.size)
        var t = startMs
        var last: P? = null
        val mm = hand.unitsPerMm
        for (it in items) {
            val first = it.pts[0]
            last?.let { l -> t += hand.rnd.uniform(it.gap.minMs, it.gap.maxMs) + 1000f * Geom.dist(l, first) / mm / it.gap.travelMmPerS }
            val samples = if (it.dot) tap(first, it.pressure) else stroke(it.pts, it.speed, it.pressure)
            val begin = t
            val brush = InkBrush(Tool.Pen, if (it.shape != null) PenStyle.Ball else pen.penStyle, it.color, pen.size * it.width, shape = it.shape, dash = it.dash)
            val rebased = samples.map { s -> s.copy(timeMillis = s.timeMillis) }
            out += WrittenStroke(InkStroke(brush, rebased), begin.roundToLong(), it.block, it.part)
            t = begin + samples.last().timeMillis
            last = P(samples.last().x, samples.last().y)
        }
        return out
    }

    private fun sample(pos: P, tMs: Float, pressure: Float): StylusSample =
        StylusSample(pos.x, pos.y, pressure.coerceIn(0.02f, 1f), hand.tilt(tMs), hand.orientation(tMs), tMs.roundToLong())

    /** A dot: a short touch with a tiny flick, mostly down-right. */
    private fun tap(center: P, heavy: Float): List<StylusSample> {
        val rnd = hand.rnd
        val dur = rnd.uniform(38f, 75f)
        val ang = rnd.uniform(-0.6f, 0.6f) + 0.9f
        val flen = rnd.uniform(0.18f, 0.45f) * hand.unitsPerMm
        val peak = hand.pressure * rnd.uniform(0.9f, 1.1f) * heavy
        val out = ArrayList<StylusSample>()
        var t = 0f
        while (t <= dur) {
            val u = t / dur
            val p = peak * sin(PI.toFloat() * min(1f, 0.12f + 0.88f * u)).pow(0.8f)
            val m = Geom.smoothstep(u)
            out += sample(P(center.x + kotlin.math.cos(ang) * flen * m, center.y + sin(ang) * flen * m), t, p)
            t += DT
        }
        return out
    }

    /** Timed samples along a polyline (page units). */
    private fun stroke(pts: Poly, speed: Float, pressureScale: Float): List<StylusSample> {
        val rnd = hand.rnd
        val mm = hand.unitsPerMm
        val ds = 0.08f * mm
        val path = Geom.resample(pts, ds)
        if (path.size < 3 || Geom.length(path) < 0.3f * mm) return tap(path[path.size / 2], pressureScale)
        val n = path.size
        val kappa = curvature(path, ds / mm)
        val vmax = hand.vmaxMm * speed
        val kc = hand.vcurveMm * speed
        val v = FloatArray(n)
        for (i in 0 until n) {
            val vk = min(vmax, kc * max(kappa[i], 1e-4f).pow(-1f / 3f)) * (1f + hand.speedNoise(i * ds))
            v[i] = max(4f, vk)
        }
        val a = hand.accelMm * speed
        val dsMm = ds / mm
        v[0] = min(v[0], rnd.uniform(6f, 16f) * speed)
        for (i in 1 until n) v[i] = min(v[i], sqrt(v[i - 1] * v[i - 1] + 2 * a * dsMm))
        v[n - 1] = min(v[n - 1], rnd.uniform(8f, 25f) * speed)
        for (i in n - 2 downTo 0) v[i] = min(v[i], sqrt(v[i + 1] * v[i + 1] + 2 * a * 1.4f * dsMm))
        val tt = FloatArray(n)
        for (i in 1 until n) tt[i] = tt[i - 1] + 1000f * dsMm / (0.5f * (v[i] + v[i - 1]))
        val dwell = rnd.uniform(0f, 10f)
        val total = tt[n - 1] + dwell
        val ramp = rnd.uniform(18f, 34f)
        val fade = rnd.uniform(22f, 40f)
        val base = hand.pressure * pressureScale * rnd.uniform(0.95f, 1.05f)
        val out = ArrayList<StylusSample>((total / DT).toInt() + 2)
        var j = 0
        var t = 0f
        while (true) {
            val tm = t - dwell
            val pos: P
            val vi: Float
            val ki: Float
            if (tm <= 0f) {
                pos = path[0]; vi = v[0]; ki = kappa[0]
            } else {
                while (j < n - 2 && tt[j + 1] < tm) j++
                val seg = tt[j + 1] - tt[j]
                val u = if (seg <= 0f) 0f else min(1f, (tm - tt[j]) / seg)
                val p0 = path[j]
                val p1 = path[j + 1]
                pos = P(p0.x + (p1.x - p0.x) * u, p0.y + (p1.y - p0.y) * u)
                vi = v[j]; ki = kappa[j]
            }
            val envIn = 0.15f + 0.85f * Geom.smoothstep(t / ramp)
            val envOut = 0.25f + 0.75f * Geom.smoothstep((total - t) / fade)
            val speedTerm = 1f - 0.22f * min(1f, vi / max(vmax, 1f))
            val curveTerm = 1f + 0.08f * min(1f, ki * 0.6f)
            val p = base * envIn * envOut * speedTerm * curveTerm * (1f + hand.pressureNoise(t))
            out += sample(pos, t, p)
            if (t >= total) break
            t += DT
        }
        return out
    }

    private fun curvature(path: Poly, dsMm: Float, win: Int = 3): FloatArray {
        val n = path.size
        val k = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - win)
            val b = min(n - 1, i + win)
            if (b - a < 2) continue
            val p0 = path[a]
            val p1 = path[i]
            val p2 = path[b]
            val a1 = atan2(p1.y - p0.y, p1.x - p0.x)
            val a2 = atan2(p2.y - p1.y, p2.x - p1.x)
            var da = abs(a2 - a1)
            if (da > PI) da = (2 * PI).toFloat() - da
            k[i] = da / max(dsMm * (b - a), 1e-6f)
        }
        val s = FloatArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - 2)
            val hi = min(n - 1, i + 2)
            var sum = 0f
            for (q in lo..hi) sum += k[q]
            s[i] = sum / (hi - lo + 1)
        }
        return s
    }

    companion object {
        const val SAMPLE_HZ = 240f
        const val DT = 1000f / SAMPLE_HZ

        /** Steps per unit for dense curves. */
        fun steps(len: Float, step: Float) = max(2, ceil(len / step).toInt())
    }
}
