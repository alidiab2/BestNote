package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.dir
import app.bestnote.engine.layout.figlib.Pen.Companion.unit
import app.bestnote.engine.layout.figlib.Pen.Role
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** FIG-LIB waves and fields: transverse, standing, longitudinal waves and pulses; field lines of point charges, uniform fields, magnetic fields. */
internal object Waves {
    fun wave(p: Params, pen: Pen): Boolean {
        val type = p.choice("type", options = listOf("transverse", "standing", "longitudinal", "pulse", "sound"), default = "transverse")
        val lam = 80f
        val amp = 24f
        when (type) {
            "longitudinal", "sound" -> {
                val n = p.num("n", "waves", default = 2f, range = 1f..5f)
                val len = n * lam
                // Lines crowd at compressions and spread at rarefactions.
                var x = 0f
                while (x <= len) {
                    pen.line(Vec2(x, -amp), Vec2(x, amp))
                    x += 3.5f + 3.2f * (1f + sin(2 * PI.toFloat() * x / lam))
                }
                p.str("lam", "lambda", "wavelength")?.let { pen.dimension(Vec2(lam * 0.25f, amp + 8f), Vec2(lam * 1.25f, amp + 8f), it, offset = 0f) }
                p.str("v", "velocity")?.let { arrowLabel(pen, Vec2(len * 0.4f, -amp - 14f), Vec2(1f, 0f), it) }
                return true
            }
            "pulse" -> {
                val pts = (0..120).map { i -> val x = i * 2f; Vec2(x, amp * 1.4f * exp(-((x - 90f) / 22f).let { it * it })) }
                pen.line(Vec2(-10f, 0f), Vec2(250f, 0f), Role.Note, dashed = true)
                pen.poly(pts, role = Role.Vector)
                p.str("v", "velocity")?.let { arrowLabel(pen, Vec2(120f, amp * 1.6f), Vec2(1f, 0f), it) }
                p.str("a", "amp", "amplitude")?.let { pen.dimension(Vec2(150f, 0f), Vec2(150f, amp * 1.4f), it, offset = 0f) }
                return true
            }
        }
        val standing = type == "standing"
        val n = p.num("n", "waves", default = if (standing) 1.5f else 2f, range = 0.5f..5f)
        val len = n * lam
        val pts = (0..(len / 2).toInt()).map { i -> val x = i * 2f; Vec2(x, amp * sin(2 * PI.toFloat() * x / lam)) }
        // Axes: x along the rest line, y up at the start.
        pen.arrow(Vec2(-6f, 0f), Vec2(len + 18f, 0f), Role.Ink)
        if (!standing) pen.arrow(Vec2(0f, -amp - 12f), Vec2(0f, amp + 18f), Role.Ink)
        pen.label(p.str("x", "xl") ?: "x", Vec2(len + 18f, 0f), Vec2(0.4f, -1f), gap = 2f)
        if (!standing) pen.label(p.str("y", "yl") ?: "y", Vec2(0f, amp + 18f), Vec2(1f, 0.3f), gap = 2f)
        pen.poly(pts, role = Role.Vector)
        if (standing) {
            pen.poly(pts.map { Vec2(it.x, -it.y) }, role = Role.Vector, dashed = true)
            if (p.flag("nodes", "node", default = true)) {
                var x = 0f
                while (x <= len + 0.1f) {
                    pen.label("N", Vec2(x, 0f), Vec2(0f, -1f), gap = 3f, size = Pen.FONT * 0.85f)
                    x += lam / 2
                }
                x = lam / 4
                while (x <= len) {
                    pen.label("A", Vec2(x, amp), Vec2(0f, 1f), gap = 3f, size = Pen.FONT * 0.85f)
                    x += lam / 2
                }
            }
        }
        // A standing wave's antinodes are already marked A.
        p.str("a", "amp", "amplitude")?.takeIf { !standing }?.let {
            // At the first trough, clear of the axis and the wavelength mark above.
            val trough = lam * 0.75f
            pen.dimension(Vec2(trough, 0f), Vec2(trough, -amp), it, offset = 0f)
        }
        p.str("lam", "lambda", "wavelength")?.let {
            val y = amp + 9f
            val a = lam * 0.25f
            val b = lam * 1.25f
            if (b <= len + 0.1f) {
                pen.line(Vec2(b, amp), Vec2(b, y + 3f), Role.Note, dashed = true)
                pen.dimension(Vec2(a, y), Vec2(b, y), it, offset = 0f)
            } else {
                pen.dimension(Vec2(0f, -amp - 8f), Vec2(lam, -amp - 8f), it, offset = 0f)
            }
        }
        p.str("v", "velocity")?.let { arrowLabel(pen, Vec2(len * 0.55f, amp + 26f), Vec2(1f, 0f), it) }
        return true
    }

    private fun arrowLabel(pen: Pen, from: Vec2, d: Vec2, text: String) {
        val to = from + d * 30f
        pen.arrow(from, to, Role.Accent)
        pen.label(text, to, d, role = Role.Accent)
    }

    fun field(p: Params, pen: Pen): Boolean {
        val label = p.str("e", "label", "name")
        p.first("b", "magnetic")?.let { r ->
            val into = r.firstOrNull()?.toString()?.lowercase()?.let { it.startsWith("i") || it == "x" || it == "×" } ?: true
            for (i in 0 until 5) for (j in 0 until 4) {
                val c = Vec2(i * 26f, j * 26f)
                if (into) {
                    pen.line(c - Vec2(4f, 4f), c + Vec2(4f, 4f))
                    pen.line(c - Vec2(4f, -4f), c + Vec2(4f, -4f))
                } else {
                    pen.circle(c, 4f)
                    pen.dot(c, 1f)
                }
            }
            pen.label(label ?: "\\vec B", Vec2(4 * 26f, 3 * 26f), Vec2(1f, 1f), gap = 4f)
            return true
        }
        p.first("wire", "current")?.let { r ->
            val out = r.firstOrNull()?.toString()?.lowercase()?.startsWith("o") ?: true
            if (out) { pen.circle(Vec2(0f, 0f), 5f); pen.dot(Vec2(0f, 0f), 1.3f) } else {
                pen.circle(Vec2(0f, 0f), 5f)
                pen.line(Vec2(-3.5f, -3.5f), Vec2(3.5f, 3.5f)); pen.line(Vec2(-3.5f, 3.5f), Vec2(3.5f, -3.5f))
            }
            for (k in 1..3) {
                val rr = 18f * k
                pen.circle(Vec2(0f, 0f), rr, Role.Vector)
                // Counter-clockwise for a current out of the page (right-hand rule).
                val a = 40f + 25f * k
                val at = dir(a) * rr
                val t = dir(a + if (out) 90f else -90f)
                pen.arrow(at - t * 3f, at + t * 3f, Role.Vector, head = 4.5f)
            }
            pen.label(p.str("i") ?: "I", Vec2(0f, 0f), Vec2(1f, -1f), gap = 6f)
            label?.let { pen.label(it, dir(40f) * 54f, Vec2(1f, 1f)) }
            return true
        }
        val charges = p.all("q", "charge").mapNotNull { r ->
            val raw = r.getOrNull(0) ?: return@mapNotNull null
            val q = Params.numOf(raw) ?: raw.toString().let { s -> if ('-' in s || '−' in s) -1f else if ('+' in s) 1f else null } ?: return@mapNotNull null
            val x = (Params.numOf(r.getOrNull(1)) ?: 0f).coerceIn(-5f, 5f)
            val y = (Params.numOf(r.getOrNull(2)) ?: 0f).coerceIn(-5f, 5f)
            Triple(q.coerceIn(-5f, 5f).takeIf { abs(it) > 1e-3f } ?: return@mapNotNull null, Vec2(x * 18f, y * 18f), raw.toString())
        }.take(6)
        if (charges.isNotEmpty()) {
            pointCharges(pen, charges)
            label?.let { pen.label(it, charges.first().second + Vec2(40f, 40f), Vec2(1f, 1f)) }
            return true
        }
        val a = p.num("uniform", "dir", "angle", default = 0f, range = -360f..360f)
        val plates = p.flag("plates", "capacitor") || p.flag("uniform").not() && p.has("plates")
        val d = dir(a)
        val nrm = d.rot90()
        val len = 120f
        for (k in -2..2) {
            val o = nrm * (k * 18f)
            pen.arrow(o - d * (len / 2), o + d * (len / 2), Role.Vector)
        }
        if (plates) {
            val pa = d * (-len / 2 - 6f)
            val pb = d * (len / 2 + 6f)
            pen.line(pa - nrm * 48f, pa + nrm * 48f)
            pen.line(pb - nrm * 48f, pb + nrm * 48f)
            pen.label("+", pa, d * -1f, gap = 3f)
            pen.label("-", pb, d, gap = 3f)
        }
        pen.label(label ?: "\\vec E", nrm * 45f + d * (len / 2), d, gap = 3f, role = Role.Vector)
        return true
    }

    private fun pointCharges(pen: Pen, charges: List<Triple<Float, Vec2, String>>) {
        fun field(at: Vec2): Vec2 {
            var e = Vec2(0f, 0f)
            for ((q, c, _) in charges) {
                val r = at - c
                val d2 = max(r.dot(r), 4f)
                e += r * (q / (d2 * kotlin.math.sqrt(d2)))
            }
            return e
        }
        val bound = 140f
        val sources = charges.filter { it.first > 0f }.ifEmpty { charges }
        val sign = if (charges.any { it.first > 0f }) 1f else -1f
        for ((q, c, _) in sources) {
            val lines = (8 * abs(q)).toInt().coerceIn(6, 14)
            for (k in 0 until lines) {
                var at = c + dir(360f * k / lines + 11f) * 9f
                val pts = arrayListOf(at)
                for (step in 0 until 220) {
                    val e = unit(field(at)) * sign
                    if (e.length() < 0.5f) break
                    val mid = at + e * 1.25f
                    at += unit(field(mid)) * (sign * 2.5f)
                    pts += at
                    if (abs(at.x) > bound || abs(at.y) > bound) break
                    if (charges.any { (oq, oc, _) -> oq * q < 0f && at.dist(oc) < 9f }) break
                }
                if (pts.size < 4) continue
                pen.poly(pts, role = Role.Vector)
                val m = pts.size / 3
                val t = unit(pts[m + 1] - pts[m]) * sign
                pen.arrow(pts[m] - t * 3f, pts[m] + t * 3f, Role.Vector, head = 4.5f)
            }
        }
        for ((q, c, raw) in charges) {
            pen.circle(c, 8f, if (q > 0f) Role.Accent else Role.Ink)
            val sym = raw.takeIf { it.length <= 3 && it.any { ch -> ch == '+' || ch == '-' || ch == '−' } } ?: if (q > 0f) "+" else "-"
            pen.label(sym, c, inside = true, size = Pen.FONT * 0.85f)
        }
    }
}
