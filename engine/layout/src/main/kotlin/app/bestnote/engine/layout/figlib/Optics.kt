package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * FIG-LIB optics: thin lenses and mirrors with their principal rays traced from the lens or mirror
 * equation (so the image is where physics puts it, real or virtual), and refraction at an interface
 * (Snell's law when the indices are numbers).
 */
internal object Optics {
    /** Drawing units per focal length. */
    private const val K = 46f

    /** A ray: an arrow to its middle (the direction it travels), then the rest of it. */
    private fun ray(pen: Pen, a: Vec2, b: Vec2, role: Role = Role.Vector) {
        val m = Pen.mid(a, b)
        pen.arrow(a, m, role, head = 4.5f)
        pen.line(m, b, role)
    }

    private fun objectAndFocus(p: Params): Triple<Float, Float, Float> {
        val f = abs(p.num("f", "focal", default = 1f, range = 0.2f..50f)).let { if (p.str("f")?.let(Params::numOf) == null) 1f else it }
        val o = p.first("obj", "object", "o")
        val d = o?.getOrNull(0)?.let(Params::numOf)?.let { abs(it) }?.coerceIn(0.2f * f, 6f * f) ?: (2.4f * f)
        val h = o?.getOrNull(1)?.let(Params::numOf)?.let { abs(it) }?.coerceIn(0.1f * f, 2f * f) ?: (0.7f * f)
        // Everything in focal lengths from here on.
        return Triple(1f, d / f, h / f)
    }

    fun lens(p: Params, pen: Pen): Boolean {
        val concave = p.choice("type", options = listOf("convex", "concave", "converging", "diverging"), default = "convex").let { it == "concave" || it == "diverging" }
        val (f0, d, h) = objectAndFocus(p)
        val f = if (concave) -f0 else f0
        val di = if (abs(d - f) < 1e-3f) Float.POSITIVE_INFINITY else 1f / (1f / f - 1f / d)
        val hi = if (di.isFinite()) -di / d * h else 0f
        val showImg = di.isFinite() && abs(di) < 7f && p.flag("img", "image", default = true)
        val xl = -(max(d, 2.2f) * K + 18f)
        val xr = (max(if (showImg && di > 0) di else 0f, 2.2f) * K + 18f)
        pen.line(Vec2(xl, 0f), Vec2(xr, 0f))
        val lh = (max(h, abs(hi).coerceAtMost(2.2f)) * K + 16f).coerceIn(50f, 110f)
        if (concave) {
            pen.poly(concaveSide(-1f, lh) + concaveSide(1f, lh).reversed(), closed = true)
        } else {
            pen.ellipse(Vec2(0f, 0f), 6f, lh)
        }
        focal(pen, f0, p.flag("2f", "twof"), "F")
        val tip = Vec2(-d * K, h * K)
        pen.arrow(Vec2(-d * K, 0f), tip, Role.Ink)
        pen.label(p.first("obj", "object", "o")?.getOrNull(2)?.let(Params::textOf) ?: "O", tip, Vec2(0f, 1f))
        val rays = p.int("rays", default = 3, range = 1..3)
        val far = xr
        // 1: parallel to the axis, then through (or as if from) the far focus.
        val hit1 = Vec2(0f, tip.y)
        ray(pen, tip, hit1)
        val focusOut = Vec2(f * K, 0f)
        outRay(pen, hit1, if (concave) unit(hit1 - focusOut) else unit(focusOut - hit1), far, xl)
        if (concave) pen.line(hit1, focusOut, Role.Vector, dashed = true)
        // 2: through the centre, undeviated.
        if (rays >= 2) {
            val dc = unit(Vec2(0f, 0f) - tip)
            ray(pen, tip, Vec2(0f, 0f))
            outRay(pen, Vec2(0f, 0f), dc, far, xl)
        }
        // 3: through (towards) the near focus, then parallel.
        if (rays >= 3 && (concave || d > 1.05f)) {
            val near = Vec2(-f * K, 0f)
            val y0 = tip.y + (0f - tip.x) * (near.y - tip.y) / (near.x - tip.x)
            if (abs(y0) < lh * 1.6f) {
                ray(pen, tip, Vec2(0f, y0))
                outRay(pen, Vec2(0f, y0), Vec2(1f, 0f), far, xl)
                if (concave) pen.line(Vec2(0f, y0), near, Role.Note, dashed = true)
            }
        }
        if (showImg) image(pen, di * K, hi * K, virtual = di < 0f, label = p.str("img", "image")?.takeIf { Params.numOf(it) == null && it != "1" } ?: "I")
        // Virtual image: the rays' backward extensions meet at it.
        if (showImg && di < 0f) {
            pen.line(hit1, Vec2(di * K, hi * K), Role.Note, dashed = true)
            pen.line(Vec2(0f, 0f), Vec2(di * K, hi * K), Role.Note, dashed = true)
        }
        return true
    }

    private fun concaveSide(side: Float, lh: Float): List<Vec2> = (0..12).map { i ->
        val y = lh - 2 * lh * i / 12f
        Vec2(side * (6f - 4f * (1f - (y / lh) * (y / lh))), y)
    }

    private fun outRay(pen: Pen, from: Vec2, d: Vec2, xr: Float, xl: Float) {
        if (abs(d.x) < 1e-3f) return
        val tx = if (d.x > 0f) (xr - from.x) / d.x else (xl - from.x) / d.x
        var end = from + d * tx
        if (abs(end.y) > 150f) end = from + d * ((150f - 0f) / max(abs(d.y), 1e-3f))
        pen.line(from, end, Role.Vector)
    }

    private fun focal(pen: Pen, f: Float, twoF: Boolean, name: String) {
        for (s in listOf(-1f, 1f)) {
            pen.dot(Vec2(s * f * K, 0f), 1.4f)
            pen.label(name, Vec2(s * f * K, 0f), Vec2(0f, -1f), gap = 3f)
            if (twoF) {
                pen.dot(Vec2(s * 2 * f * K, 0f), 1.4f)
                pen.label("2$name", Vec2(s * 2 * f * K, 0f), Vec2(0f, -1f), gap = 3f)
            }
        }
    }

    private fun image(pen: Pen, x: Float, y: Float, virtual: Boolean, label: String) {
        pen.arrow(Vec2(x, 0f), Vec2(x, y), Role.Accent, dashed = virtual)
        pen.label(label, Vec2(x, y), Vec2(0f, if (y >= 0f) 1f else -1f), role = Role.Accent)
    }

    fun mirror(p: Params, pen: Pen): Boolean {
        val type = p.choice("type", options = listOf("concave", "convex", "plane", "flat"), default = "concave")
        val (f0, d, h) = objectAndFocus(p)
        val tip = Vec2(-d * K, h * K)
        val lh = (h * K * 1.6f).coerceIn(50f, 110f)
        if (type == "plane" || type == "flat") {
            pen.surface(Vec2(0f, -lh), Vec2(0f, lh), side = -1)
            pen.line(Vec2(-d * K - 20f, 0f), Vec2(d * K + 20f, 0f))
            pen.arrow(Vec2(-d * K, 0f), tip, Role.Ink)
            pen.label("O", tip, Vec2(0f, 1f))
            image(pen, d * K, h * K, virtual = true, label = "I")
            for (y in listOf(tip.y * 0.3f, -tip.y * 0.4f)) {
                val hit = Vec2(0f, y)
                ray(pen, tip, hit)
                val back = unit(hit - Vec2(d * K, h * K))
                val out = hit + back * (d * K * 0.9f)
                pen.line(hit, out, Role.Vector)
                pen.line(hit, Vec2(d * K, h * K), Role.Note, dashed = true)
            }
            return true
        }
        val concave = type == "concave"
        val f = if (concave) f0 else -f0
        val r = 2f * f0 * K
        // The mirror: a shallow arc, its centre of curvature on the object side (concave) or behind it (convex).
        val cx = if (concave) -r else r
        val half = Math.toDegrees(asin((lh / r).coerceAtMost(0.95f).toDouble())).toFloat()
        if (concave) pen.arc(Vec2(cx, 0f), r, -half, half) else pen.arc(Vec2(cx, 0f), r, 180f - half, 180f + half)
        fun surfX(y: Float) = if (concave) cx + sqrt(max(0f, r * r - y * y)) else cx - sqrt(max(0f, r * r - y * y))
        val xl = -(max(d, 2.4f) * K + 18f)
        pen.line(Vec2(xl, 0f), Vec2(if (concave) 12f else 2.4f * K, 0f))
        for ((x, n) in listOf(-f0 * K to "F", -2f * f0 * K to "C")) {
            val xx = if (concave) x else -x
            pen.dot(Vec2(xx, 0f), 1.4f)
            pen.label(n, Vec2(xx, 0f), Vec2(0f, -1f), gap = 3f)
        }
        pen.arrow(Vec2(-d * K, 0f), tip, Role.Ink)
        pen.label("O", tip, Vec2(0f, 1f))
        val di = if (abs(d - f) < 1e-3f) Float.POSITIVE_INFINITY else 1f / (1f / f - 1f / d)
        val focus = Vec2(-f * K, 0f)
        // 1: parallel in, out through (or as if from) the focus.
        val hit1 = Vec2(surfX(tip.y), tip.y)
        ray(pen, tip, hit1)
        val out1 = if (concave) unit(focus - hit1) else unit(hit1 - focus)
        val ylim = max(lh, tip.y) * 1.4f
        val imgTip = if (di.isFinite()) Vec2(-di * K, -di / d * h * K) else null
        // Reflected rays end at the drawing's edge; virtual images are where their dashed extensions meet.
        fun reach(from: Vec2, dd: Vec2): Float {
            var t = 260f
            if (dd.x < -1e-3f) t = min(t, (xl - from.x) / dd.x)
            if (abs(dd.y) > 1e-3f) t = min(t, ((if (dd.y > 0f) ylim else -ylim) - from.y) / dd.y)
            return t.coerceAtLeast(0f)
        }
        pen.line(hit1, hit1 + out1 * reach(hit1, out1), Role.Vector)
        if (di < 0f && imgTip != null) pen.line(hit1, imgTip, Role.Note, dashed = true)
        // 2: to the vertex, reflected symmetrically about the axis.
        if (p.int("rays", default = 2, range = 1..3) >= 2) {
            val v = Vec2(surfX(0f), 0f)
            ray(pen, tip, v)
            val inn = unit(v - tip)
            val out2 = Vec2(-inn.x, inn.y)
            pen.line(v, v + out2 * reach(v, out2), Role.Vector)
            if (di < 0f && imgTip != null) pen.line(v, imgTip, Role.Note, dashed = true)
        }
        if (di.isFinite() && abs(di) < 7f) image(pen, -di * K, -di / d * h * K, virtual = di < 0f, label = "I")
        return true
    }

    fun refraction(p: Params, pen: Pen): Boolean {
        val n = p.first("n", "media", "index")
        val n1t = n?.getOrNull(0)?.let(Params::textOf) ?: "n_1"
        val n2t = n?.getOrNull(1)?.let(Params::textOf) ?: "n_2"
        val i = p.num("i", "incidence", "a", default = 40f, range = 1f..85f)
        val n1 = Params.numOf(n1t)
        val n2 = Params.numOf(n2t)
        val sinR = if (n1 != null && n2 != null && n2 > 0f) n1 * kotlin.math.sin(Math.toRadians(i.toDouble())).toFloat() / n2 else null
        val tir = sinR != null && sinR > 1f
        val r = p.numOrNull("r", "refraction", range = 0f..89f) ?: sinR?.takeIf { !tir }?.let { Math.toDegrees(asin(it.toDouble())).toFloat() } ?: (if (tir) null else i * 0.68f)
        val len = 90f
        pen.line(Vec2(-110f, 0f), Vec2(110f, 0f))
        pen.line(Vec2(0f, -85f), Vec2(0f, 85f), Role.Note, dashed = true)
        pen.label(n1t, Vec2(-95f, 0f), Vec2(0f, 1f), gap = 6f)
        pen.label(n2t, Vec2(-95f, 0f), Vec2(0f, -1f), gap = 6f)
        val start = dir(90f + i) * len
        pen.arrow(start, Pen.mid(start, Vec2(0f, 0f)), Role.Vector, head = 4.5f)
        pen.line(Pen.mid(start, Vec2(0f, 0f)), Vec2(0f, 0f), Role.Vector)
        val ang = p.first("ang", "angles")
        pen.angle(Vec2(0f, 0f), 90f, 90f + i, r = 26f, label = ang?.getOrNull(0)?.let(Params::textOf) ?: "\\theta_1")
        if (r != null) {
            val end = dir(270f + r) * len
            pen.arrow(Vec2(0f, 0f), end, Role.Vector)
            pen.angle(Vec2(0f, 0f), 270f, 270f + r, r = 26f, label = ang?.getOrNull(1)?.let(Params::textOf) ?: "\\theta_2")
        }
        if (p.flag("reflect", "reflection") || tir) {
            val end = dir(90f - i) * (len * 0.8f)
            pen.arrow(Vec2(0f, 0f), end, Role.Vector, dashed = !tir)
        }
        return true
    }
}
