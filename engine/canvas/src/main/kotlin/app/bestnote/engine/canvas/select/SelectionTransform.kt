package app.bestnote.engine.canvas.select

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine map in page points: x' = a·x + c·y + tx, y' = b·x + d·y + ty (the same layout as
 * android.graphics.Matrix's MSCALE_X, MSKEW_Y, MSKEW_X, MSCALE_Y). Pure Kotlin.
 */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** How much lengths grow (the geometric mean of the axis scales): brush sizes scale by this. */
    val lengthScale: Float get() = sqrt(abs(a * d - b * c))

    /** This map, then [next]. */
    fun then(next: Affine) = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        tx = next.a * tx + next.c * ty + next.tx,
        ty = next.b * tx + next.d * ty + next.ty,
    )

    companion object {
        val IDENTITY = Affine()

        fun translate(dx: Float, dy: Float) = Affine(tx = dx, ty = dy)

        fun scaleAbout(s: Float, px: Float, py: Float) = Affine(a = s, d = s, tx = px - s * px, ty = py - s * py)

        fun rotateAbout(radians: Float, px: Float, py: Float): Affine {
            val cs = cos(radians)
            val sn = sin(radians)
            return Affine(a = cs, b = sn, c = -sn, d = cs, tx = px - cs * px + sn * py, ty = py - sn * px - cs * py)
        }
    }
}

/** Where a pen grabbed the selection. */
enum class Grip {
    /** Inside the box: drag moves. */
    Move,

    /** A corner: uniform resize about the opposite corner. */
    TopLeft, TopRight, BottomRight, BottomLeft,

    /** The knob above the box: rotate about its center. */
    Rotate,
}

/** Rotation snapping (CANVAS-09): within [THRESHOLD_DEG] of a multiple of 45° the angle locks to it. */
object RotationSnap {
    const val STEP_DEG = 45f
    const val THRESHOLD_DEG = 5f

    /** @return the snapped angle (radians) and the snapped step in degrees (0, 45, 90, ... ), or null when free. */
    fun snap(radians: Float): Pair<Float, Int?> {
        val deg = radians * DEG_PER_RAD
        val nearest = (deg / STEP_DEG).roundToInt() * STEP_DEG
        return if (abs(deg - nearest) <= THRESHOLD_DEG) {
            nearest / DEG_PER_RAD to normalize(nearest.roundToInt())
        } else {
            radians to null
        }
    }

    private fun normalize(deg: Int) = ((deg % 360) + 360) % 360

    private const val DEG_PER_RAD = (180.0 / PI).toFloat()
}

/**
 * One drag on a selection (CANVAS-09), in page points. [box] is the selection's box when the drag
 * started and ([startX], [startY]) the pen-down point. [update] gives the transform for the pen at
 * (x, y); rotation snaps (see [RotationSnap]) and [snappedDeg] says to which angle, so the view can
 * tick the haptics once per new snap. Resizing is uniform and never shrinks the box below [minSize].
 */
class TransformGesture(
    val grip: Grip,
    private val box: Box,
    private val startX: Float,
    private val startY: Float,
    private val minSize: Float = MIN_SIZE,
) {
    var transform: Affine = Affine.IDENTITY
        private set

    /** The 45° step rotation is locked to, or null when free (or not rotating). */
    var snappedDeg: Int? = null
        private set

    /** Total rotation in radians (after snapping), for the rotate grip. */
    var angle: Float = 0f
        private set

    fun update(x: Float, y: Float): Affine {
        transform = when (grip) {
            Grip.Move -> Affine.translate(x - startX, y - startY)
            Grip.Rotate -> rotate(x, y)
            else -> resize(x, y)
        }
        return transform
    }

    private fun rotate(x: Float, y: Float): Affine {
        val cx = box.centerX
        val cy = box.centerY
        // The knob sits straight above the center: angle 0 means "no rotation".
        val raw = atan2(y - cy, x - cx) - atan2(startY - cy, startX - cx)
        val (snapped, deg) = RotationSnap.snap(wrap(raw))
        angle = snapped
        snappedDeg = deg
        return Affine.rotateAbout(snapped, cx, cy)
    }

    private fun resize(x: Float, y: Float): Affine {
        // The pivot is the corner opposite the grabbed one.
        val (px, py) = when (grip) {
            Grip.TopLeft -> box.maxX to box.maxY
            Grip.TopRight -> box.minX to box.maxY
            Grip.BottomRight -> box.minX to box.minY
            else -> box.maxX to box.minY
        }
        val d0 = hypot(startX - px, startY - py)
        if (d0 < EPS) return Affine.IDENTITY
        // Project the pen onto the pivot -> start direction: dragging along the diagonal scales.
        val ux = (startX - px) / d0
        val uy = (startY - py) / d0
        val along = (x - px) * ux + (y - py) * uy
        val minScale = kotlin.math.min(1f, minSize / max(max(box.width, box.height), EPS))
        val s = max(along / d0, minScale)
        return Affine.scaleAbout(s, px, py)
    }

    private fun wrap(r: Float): Float {
        var v = r
        while (v > PI) v -= (2 * PI).toFloat()
        while (v < -PI) v += (2 * PI).toFloat()
        return v
    }

    companion object {
        /** The smallest a selection can be resized to, in page points. */
        const val MIN_SIZE = 6f
        private const val EPS = 1e-3f
    }
}
