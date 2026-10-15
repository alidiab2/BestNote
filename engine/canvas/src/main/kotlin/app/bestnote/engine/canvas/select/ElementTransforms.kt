package app.bestnote.engine.canvas.select

import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextElement
import app.bestnote.core.model.normalizeAngle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Geometry of text boxes and images for selection and the lasso's transforms (TEXT-01, MEDIA-02). Pure Kotlin. */
object ElementTransforms {
    /**
     * [e] after the lasso's move/resize/rotate [m]: a similarity (translate, uniform scale, rotate), so the
     * frame stays a rectangle. Text grows and shrinks with the box. Locked elements stay as they are.
     */
    fun transformed(e: PageElement, m: Affine): PageElement {
        if (e.locked || m.isIdentity) return e
        val k = m.lengthScale
        val moved = e.placed(
            cx = m.mapX(e.cx, e.cy),
            cy = m.mapY(e.cx, e.cy),
            width = e.width * k,
            height = e.height * k,
            rotation = normalizeAngle(e.rotation + atan2(m.b, m.a)),
        )
        return if (moved is TextElement && k != 1f) {
            moved.copy(sizePt = (moved.sizePt * k).coerceIn(TextElement.MIN_SIZE_PT / 2f, TextElement.MAX_SIZE_PT * 2f))
        } else {
            moved
        }
    }

    /** The four corners in page points, clockwise from the top-left of the unrotated frame: x0,y0,x1,y1,... */
    fun corners(e: PageElement): FloatArray {
        val hw = e.width / 2f
        val hh = e.height / 2f
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val out = FloatArray(CORNER_VALUES)
        val lx = floatArrayOf(-hw, hw, hw, -hw)
        val ly = floatArrayOf(-hh, -hh, hh, hh)
        for (i in 0 until 4) {
            out[2 * i] = e.cx + lx[i] * c - ly[i] * s
            out[2 * i + 1] = e.cy + lx[i] * s + ly[i] * c
        }
        return out
    }

    /** The axis-aligned box around [e]'s rotated frame. */
    fun bounds(e: PageElement): Box {
        val p = corners(e)
        return Box.of(floatArrayOf(p[0], p[2], p[4], p[6]), floatArrayOf(p[1], p[3], p[5], p[7]))!!
    }

    /** Is page point ([x], [y]) inside [e]'s rotated frame? */
    fun contains(e: PageElement, x: Float, y: Float): Boolean {
        val dx = x - e.cx
        val dy = y - e.cy
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        return kotlin.math.abs(lx) <= e.width / 2f && kotlin.math.abs(ly) <= e.height / 2f
    }

    /**
     * Does a lasso select [e]? The frame's outline is probed like a stroke's centerline: more than half of it
     * must be enclosed (CANVAS-08), or the frame must be entirely inside.
     */
    fun selectedBy(poly: LassoPolygon, e: PageElement): Boolean {
        val p = corners(e)
        val xs = floatArrayOf(p[0], p[2], p[4], p[6], p[0])
        val ys = floatArrayOf(p[1], p[3], p[5], p[7], p[1])
        return poly.selects(xs, ys) || poly.contains(e.cx, e.cy)
    }

    private const val CORNER_VALUES = 8
}
