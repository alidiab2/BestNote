package app.bestnote.engine.handwriting

import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in page units, y down. */
data class P(val x: Float, val y: Float) {
    operator fun plus(o: P) = P(x + o.x, y + o.y)
    operator fun minus(o: P) = P(x - o.x, y - o.y)
    operator fun times(s: Float) = P(x * s, y * s)
}

internal typealias Poly = List<P>

/** Deterministic randomness for one page: the same seed always writes the same page. */
internal class Rand(seed: Long) {
    private val r = Random(seed)
    fun uniform(a: Float, b: Float): Float = a + (b - a) * r.nextFloat()
    fun gauss(mean: Float, sd: Float): Float = mean + sd * r.nextGaussian().toFloat()
    fun nextLong(): Long = r.nextLong()
}

internal object Geom {
    fun dist(a: P, b: P): Float = hypot(b.x - a.x, b.y - a.y)

    fun length(p: Poly): Float {
        var s = 0f
        for (i in 0 until p.size - 1) s += dist(p[i], p[i + 1])
        return s
    }

    fun dedupe(p: Poly, eps: Float = 1e-4f): Poly {
        if (p.isEmpty()) return p
        val out = ArrayList<P>(p.size)
        out += p[0]
        for (i in 1 until p.size) if (dist(out.last(), p[i]) > eps) out += p[i]
        return out
    }

    /** Uniform arc-length resampling at [step]. */
    fun resample(input: Poly, step: Float): Poly {
        val p = dedupe(input)
        if (p.size < 2) return p
        val out = ArrayList<P>()
        out += p[0]
        var carry = 0f
        for (i in 0 until p.size - 1) {
            val a = p[i]
            val b = p[i + 1]
            val seg = dist(a, b)
            var d = step - carry
            while (d <= seg) {
                val w = d / seg
                out += P(a.x + (b.x - a.x) * w, a.y + (b.y - a.y) * w)
                d += step
            }
            carry = seg - (d - step)
        }
        if (dist(out.last(), p.last()) > step * 0.25f) out += p.last()
        return out
    }

    /** Centripetal Catmull-Rom through [input], about [step] apart. No overshoot loops. */
    fun catmullRom(input: Poly, step: Float): Poly {
        val pts = dedupe(input)
        if (pts.size < 2) return pts
        if (pts.size == 2) return resample(pts, step)
        val ext = ArrayList<P>(pts.size + 2)
        ext += P(2 * pts[0].x - pts[1].x, 2 * pts[0].y - pts[1].y)
        ext += pts
        ext += P(2 * pts.last().x - pts[pts.size - 2].x, 2 * pts.last().y - pts[pts.size - 2].y)
        val out = ArrayList<P>()
        out += pts[0]
        for (i in 1 until ext.size - 2) {
            val p0 = ext[i - 1]
            val p1 = ext[i]
            val p2 = ext[i + 1]
            val p3 = ext[i + 2]
            val t0 = 0f
            val t1 = t0 + max(dist(p0, p1), 1e-4f).pow(0.5f)
            val t2 = t1 + max(dist(p1, p2), 1e-4f).pow(0.5f)
            val t3 = t2 + max(dist(p2, p3), 1e-4f).pow(0.5f)
            val n = max(2, ceil(dist(p1, p2) / step).toInt())
            for (k in 1..n) {
                val t = t1 + (t2 - t1) * k / n
                val a1 = lerp(p0, p1, t0, t1, t)
                val a2 = lerp(p1, p2, t1, t2, t)
                val a3 = lerp(p2, p3, t2, t3, t)
                val b1 = lerp(a1, a2, t0, t2, t)
                val b2 = lerp(a2, a3, t1, t3, t)
                out += lerp(b1, b2, t1, t2, t)
            }
        }
        return out
    }

    private fun lerp(a: P, b: P, ta: Float, tb: Float, t: Float): P {
        if (tb - ta < 1e-9f) return a
        val w = (t - ta) / (tb - ta)
        return P(a.x + (b.x - a.x) * w, a.y + (b.y - a.y) * w)
    }

    /** Displaces points by smooth 2-D noise along arc length: hand tremor and imprecision. */
    fun wobble(p: Poly, rnd: Rand, amp: Float, wavelength: Float): Poly {
        val nx = SmoothNoise(rnd, wavelength, amp)
        val ny = SmoothNoise(rnd, wavelength, amp)
        var s = 0f
        return p.mapIndexed { i, q ->
            if (i > 0) s += dist(p[i - 1], q)
            P(q.x + nx(s), q.y + ny(s))
        }
    }

    fun bounds(polys: Iterable<Poly>): FloatArray? {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var any = false
        for (p in polys) for (q in p) {
            any = true
            if (q.x < l) l = q.x
            if (q.y < t) t = q.y
            if (q.x > r) r = q.x
            if (q.y > b) b = q.y
        }
        return if (any) floatArrayOf(l, t, r, b) else null
    }

    /** Points on a quadratic Bézier. */
    fun quad(a: P, c: P, b: P, step: Float): Poly {
        val n = max(4, ceil((dist(a, c) + dist(c, b)) / step).toInt())
        return (0..n).map { i ->
            val t = i.toFloat() / n
            val m = 1 - t
            P(m * m * a.x + 2 * m * t * c.x + t * t * b.x, m * m * a.y + 2 * m * t * c.y + t * t * b.y)
        }
    }

    fun cubic(a: P, c1: P, c2: P, b: P, step: Float): Poly {
        val n = max(6, ceil((dist(a, c1) + dist(c1, c2) + dist(c2, b)) / step).toInt())
        return (0..n).map { i ->
            val t = i.toFloat() / n
            val m = 1 - t
            P(
                m * m * m * a.x + 3 * m * m * t * c1.x + 3 * m * t * t * c2.x + t * t * t * b.x,
                m * m * m * a.y + 3 * m * m * t * c1.y + 3 * m * t * t * c2.y + t * t * t * b.y,
            )
        }
    }

    fun smoothstep(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }

    fun sq(x: Float) = sqrt(x)
}

/** 1-D band-limited noise: a sum of a few random sinusoids. Deterministic per [Rand]. */
internal class SmoothNoise(rnd: Rand, wavelength: Float, amplitude: Float, octaves: Int = 3) {
    private val k = FloatArray(octaves)
    private val ph = FloatArray(octaves)
    private val a = FloatArray(octaves)

    init {
        for (o in 0 until octaves) {
            val wl = wavelength / 1.9f.pow(o) * rnd.uniform(0.8f, 1.25f)
            k[o] = (2 * PI / wl).toFloat()
            ph[o] = rnd.uniform(0f, (2 * PI).toFloat())
            a[o] = amplitude / 1.8f.pow(o)
        }
    }

    operator fun invoke(s: Float): Float {
        var v = 0f
        for (o in k.indices) v += a[o] * sin(k[o] * s + ph[o])
        return v
    }
}

internal fun circlePoint(c: P, r: Float, angle: Float) = P(c.x + cos(angle) * r, c.y + sin(angle) * r)
