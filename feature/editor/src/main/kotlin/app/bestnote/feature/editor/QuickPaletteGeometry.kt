package app.bestnote.feature.editor

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Where the S Pen quick palette opens (pure, unit-tested). The palette is a disk of [radius] that
 * must never cover the hover point: its center sits [radius] + [gap] away from the pen tip,
 * preferably up and away from the writing hand, and flips to another side at screen edges.
 */
object QuickPaletteGeometry {
    data class Point(val x: Float, val y: Float)

    private val diagonal = 1f / sqrt(2f)

    /**
     * @param hover pen tip, in the same coordinates as [width] x [height] (the screen/root).
     * @param leftHanded the hand rests left of the tip, so prefer opening to the right.
     * @return the palette's center.
     */
    fun place(hover: Point, radius: Float, gap: Float, margin: Float, width: Float, height: Float, leftHanded: Boolean): Point {
        val d = radius + gap
        val away = if (leftHanded) 1f else -1f // horizontal direction away from the hand
        val directions = listOf(
            away * diagonal to -diagonal, // up, away from the hand
            -away * diagonal to -diagonal, // up, over the hand
            away to 0f,
            -away to 0f,
            away * diagonal to diagonal,
            -away * diagonal to diagonal,
            0f to -1f,
            0f to 1f,
        )
        for ((dx, dy) in directions) {
            val c = Point(hover.x + dx * d, hover.y + dy * d)
            if (fits(c, radius, margin, width, height)) return c
        }
        // Nothing fits (tiny window): clamp, then push away from the tip so it stays uncovered if possible.
        val first = directions.first()
        val clamped = Point(
            (hover.x + first.first * d).coerceIn(radius + margin, (width - radius - margin).coerceAtLeast(radius + margin)),
            (hover.y + first.second * d).coerceIn(radius + margin, (height - radius - margin).coerceAtLeast(radius + margin)),
        )
        return clamped
    }

    fun fits(center: Point, radius: Float, margin: Float, width: Float, height: Float): Boolean =
        center.x - radius >= margin && center.y - radius >= margin &&
            center.x + radius <= width - margin && center.y + radius <= height - margin

    fun covers(center: Point, radius: Float, point: Point): Boolean = hypot(center.x - point.x, center.y - point.y) < radius
}
