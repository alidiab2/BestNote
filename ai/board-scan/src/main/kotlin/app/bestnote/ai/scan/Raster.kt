package app.bestnote.ai.scan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An ARGB image (one Int per pixel, row-major), the shape Android's `Bitmap.getPixels` fills. */
class Raster(val width: Int, val height: Int, val argb: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "Bad raster ${width}x$height (${argb.size})" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    /** Averages [factor]x[factor] blocks: a cheap, alias-free shrink. */
    fun shrink(longSide: Int): Raster {
        val factor = max(1, (max(width, height) + longSide - 1) / longSide)
        if (factor == 1) return this
        val w = max(1, width / factor)
        val h = max(1, height / factor)
        val out = IntArray(w * h)
        val n = factor * factor
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0
            var g = 0
            var b = 0
            for (dy in 0 until factor) {
                val row = (y * factor + dy) * width
                for (dx in 0 until factor) {
                    val c = argb[row + x * factor + dx]
                    r += c shr 16 and 0xFF
                    g += c shr 8 and 0xFF
                    b += c and 0xFF
                }
            }
            out[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Raster(w, h, out)
    }

    /** Luma (0..255) per pixel. */
    fun luma(): FloatArray = FloatArray(argb.size) { lumaOf(argb[it]) }

    companion object {
        fun lumaOf(c: Int): Float = 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)

        fun rgb(r: Float, g: Float, b: Float): Int =
            (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

        private fun clamp(v: Float) = min(255, max(0, v.roundToInt()))
    }
}

/** A point in normalised image coordinates (0..1 of width and height). */
data class Pt(val x: Float, val y: Float)

/**
 * A board or page outline: four corners in normalised image coordinates, clockwise from the
 * top-left as seen on the photo.
 */
data class Quad(val topLeft: Pt, val topRight: Pt, val bottomRight: Pt, val bottomLeft: Pt) {
    val corners: List<Pt> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun with(index: Int, p: Pt): Quad = when (index) {
        0 -> copy(topLeft = p)
        1 -> copy(topRight = p)
        2 -> copy(bottomRight = p)
        else -> copy(bottomLeft = p)
    }

    /** Area in normalised units (1 = the whole photo). */
    val area: Float get() = Geometry.area(corners)

    /** True when the four corners make a convex, clockwise outline (a quad the warp can flatten). */
    val isConvex: Boolean get() = Geometry.isConvex(corners)

    companion object {
        /** The whole photo, inset by [inset] on every side. */
        fun full(inset: Float = 0.04f) = Quad(Pt(inset, inset), Pt(1 - inset, inset), Pt(1 - inset, 1 - inset), Pt(inset, 1 - inset))

        /** Four points in any order → clockwise from the top-left (the corner nearest the photo's top-left). */
        fun ordered(points: List<Pt>): Quad {
            require(points.size == 4)
            val cx = points.sumOf { it.x.toDouble() } / 4
            val cy = points.sumOf { it.y.toDouble() } / 4
            val byAngle = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) } // clockwise on screen (y down)
            val start = byAngle.indices.minBy { byAngle[it].x + byAngle[it].y }
            val p = List(4) { byAngle[(start + it) % 4] }
            return Quad(p[0], p[1], p[2], p[3])
        }
    }
}

internal object Geometry {
    fun area(p: List<Pt>): Float {
        var s = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return kotlin.math.abs(s) / 2
    }

    fun isConvex(p: List<Pt>): Boolean {
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val c = p[(i + 2) % p.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (kotlin.math.abs(cross) < 1e-7f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }
}
