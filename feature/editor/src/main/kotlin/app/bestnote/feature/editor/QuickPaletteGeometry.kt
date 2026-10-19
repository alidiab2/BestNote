package app.bestnote.feature.editor

import kotlin.math.hypot

/**
 * Where the S Pen quick palette opens (pure, unit-tested). The palette is a disk of [radius] that
 * opens centered on the pen tip, clamped so it stays [margin] inside the [width] x [height] screen.
 */
object QuickPaletteGeometry {
    data class Point(val x: Float, val y: Float)

    /**
     * @param hover pen tip, in the same coordinates as [width] x [height] (the screen/root).
     * @return the palette's center: the tip itself, unless that would push the disk off screen.
     */
    fun place(hover: Point, radius: Float, margin: Float, width: Float, height: Float): Point {
        val min = radius + margin
        return Point(
            hover.x.coerceIn(min, (width - min).coerceAtLeast(min)),
            hover.y.coerceIn(min, (height - min).coerceAtLeast(min)),
        )
    }

    fun covers(center: Point, radius: Float, point: Point): Boolean = hypot(center.x - point.x, center.y - point.y) < radius
}
