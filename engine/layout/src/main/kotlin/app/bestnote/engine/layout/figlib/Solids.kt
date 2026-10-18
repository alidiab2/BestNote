package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.figlib.Pen.Companion.mid
import app.bestnote.engine.layout.figlib.Pen.Role

/** FIG-LIB 3D solids as school wireframes: oblique projection, hidden edges dashed, edge and radius labels beside them. */
internal object Solids {
    /** The receding axis of the oblique projection. */
    private val DEPTH = Vec2(0.5f, 0.36f)

    fun draw(p: Params, pen: Pen): Boolean {
        val type = p.choice("type", "kind", options = listOf("cube", "cuboid", "box", "prism", "cylinder", "cone", "sphere", "ball", "pyramid"), default = "cuboid")
        when (type) {
            "cube", "cuboid", "box" -> box(p, pen, cube = type == "cube")
            "prism" -> prism(p, pen)
            "cylinder" -> cylinder(p, pen)
            "cone" -> cone(p, pen)
            "sphere", "ball" -> sphere(p, pen)
            "pyramid" -> pyramid(p, pen)
        }
        return true
    }

    private fun box(p: Params, pen: Pen, cube: Boolean) {
        val w = if (cube) 80f else 110f
        val h = if (cube) 80f else 66f
        val d = DEPTH * (if (cube) 80f else 70f)
        val a = Vec2(0f, 0f)
        val b = Vec2(w, 0f)
        val c = Vec2(w, h)
        val e = Vec2(0f, h)
        pen.poly(listOf(a, b, c, e), closed = true)
        pen.poly(listOf(e, e + d, c + d, c))
        pen.poly(listOf(b, b + d, c + d))
        // The hidden corner: three dashed edges.
        val hid = a + d
        pen.line(a, hid, dashed = true)
        pen.line(hid, b + d, dashed = true)
        pen.line(hid, e + d, dashed = true)
        p.str("a", "w", "width")?.let { pen.label(it, mid(a, b), Vec2(0f, -1f)) }
        p.str("b", "h", "height")?.let { pen.label(it, mid(a, e), Vec2(-1f, 0f)) }
        p.str("c", "d", "depth")?.let { pen.label(it, mid(b, b + d), Vec2(1f, -0.6f)) }
    }

    private fun prism(p: Params, pen: Pen) {
        val a = Vec2(0f, 0f)
        val b = Vec2(100f, 0f)
        val c = Vec2(40f, 70f)
        val d = DEPTH * 80f
        pen.poly(listOf(a, b, c), closed = true)
        pen.poly(listOf(c, c + d, b + d, b))
        pen.line(a + d, c + d, dashed = true)
        pen.line(a + d, b + d, dashed = true)
        pen.line(a, a + d, dashed = true)
        p.str("a", "w")?.let { pen.label(it, mid(a, b), Vec2(0f, -1f)) }
        p.str("h", "height")?.let {
            val foot = Vec2(c.x, 0f)
            pen.line(c, foot, Role.Note, dashed = true)
            pen.rightAngle(foot, 0f, 90f, 5f, Role.Note)
            pen.label(it, mid(c, foot), Vec2(1f, 0f))
        }
        p.str("l", "c", "length")?.let { pen.label(it, mid(b, b + d), Vec2(1f, -0.6f)) }
    }

    /** An ellipse seen obliquely: its front half solid, its back half dashed (when [hidden]). */
    private fun rim(pen: Pen, c: Vec2, rx: Float, ry: Float, hidden: Boolean) {
        pen.poly(Pen.arcPoints(c, rx, ry, 180f, 360f))
        pen.poly(Pen.arcPoints(c, rx, ry, 0f, 180f), dashed = hidden)
    }

    private fun cylinder(p: Params, pen: Pen) {
        val rx = 45f
        val ry = 14f
        val h = 100f
        val bot = Vec2(0f, 0f)
        val top = Vec2(0f, h)
        rim(pen, bot, rx, ry, hidden = true)
        pen.ellipse(top, rx, ry)
        pen.line(Vec2(-rx, 0f), Vec2(-rx, h))
        pen.line(Vec2(rx, 0f), Vec2(rx, h))
        p.str("r", "radius")?.let {
            pen.line(top, top + Vec2(rx, 0f))
            pen.dot(top, 1.2f)
            pen.label(it, top + Vec2(rx / 2, 0f), Vec2(0f, 1f), gap = 1.5f)
        }
        p.str("h", "height")?.let { pen.dimension(Vec2(rx, 0f), Vec2(rx, h), it, offset = -12f) }
    }

    private fun cone(p: Params, pen: Pen) {
        val rx = 48f
        val ry = 15f
        val h = 110f
        val apex = Vec2(0f, h)
        rim(pen, Vec2(0f, 0f), rx, ry, hidden = true)
        pen.line(Vec2(-rx, 0f), apex)
        pen.line(Vec2(rx, 0f), apex)
        p.str("h", "height")?.let {
            pen.line(apex, Vec2(0f, 0f), Role.Ink, dashed = true)
            pen.rightAngle(Vec2(0f, 0f), 0f, 90f, 5f)
            pen.label(it, Vec2(0f, h * 0.45f), Vec2(-1f, 0f), gap = 2f)
        }
        p.str("r", "radius")?.let {
            pen.line(Vec2(0f, 0f), Vec2(rx, 0f), dashed = true)
            pen.label(it, Vec2(rx / 2, 0f), Vec2(0f, -1f), gap = 2f)
        }
        p.str("l", "slant", "s")?.let { pen.label(it, mid(Vec2(rx, 0f), apex), Vec2(1f, 0.4f), gap = 2f) }
    }

    private fun sphere(p: Params, pen: Pen) {
        val r = 60f
        pen.circle(Vec2(0f, 0f), r)
        rim(pen, Vec2(0f, 0f), r, r * 0.3f, hidden = true)
        pen.dot(Vec2(0f, 0f), 1.4f)
        p.str("r", "radius")?.let {
            pen.line(Vec2(0f, 0f), Vec2(r * 0.8f, r * 0.6f))
            pen.label(it, Vec2(r * 0.4f, r * 0.3f), Vec2(-0.6f, 0.8f), gap = 1.5f)
        }
    }

    private fun pyramid(p: Params, pen: Pen) {
        val a = Vec2(0f, 0f)
        val b = Vec2(100f, 0f)
        val d = DEPTH * 80f
        val c = b + d
        val e = a + d
        val apex = Vec2((a.x + c.x) / 2, 110f)
        pen.line(a, b)
        pen.line(b, c)
        pen.line(c, e, dashed = true)
        pen.line(e, a, dashed = true)
        pen.line(a, apex)
        pen.line(b, apex)
        pen.line(c, apex)
        pen.line(e, apex, dashed = true)
        p.str("h", "height")?.let {
            val foot = Vec2((a.x + c.x) / 2, (a.y + c.y) / 2)
            pen.line(apex, foot, Role.Note, dashed = true)
            pen.label(it, mid(apex, foot), Vec2(1f, 0f))
        }
        p.str("a", "w")?.let { pen.label(it, mid(a, b), Vec2(0f, -1f)) }
    }
}
