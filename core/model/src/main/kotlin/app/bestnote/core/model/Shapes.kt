package app.bestnote.core.model

/**
 * CANVAS-17: what a clean shape stroke is, so other shapes can glue to it and lines can stay attached to it.
 * Stored with the stroke's [InkBrush.shape]; strokes without it are plain ink.
 *
 * @property id unique on the page; [ShapeLink]s point at it.
 * @property kind the recognized kind's name (`Line`, `Arrow`, `Circle`, `Ellipse`, `Rectangle`, `Triangle`,
 *   `Polygon`, `Diamond`, `Star`, `Polyline`); unknown names are kept and treated as a closed polygon.
 * @property points key points in page points, flattened `x0, y0, x1, y1, …`. Lines: both ends. Arrows: shaft
 *   start, tip, then the barbs. Circles and ellipses: center, end of the first semi-axis, end of the second.
 *   Polygons (closed or open): the corners in drawing order. They move with the stroke (any affine transform).
 * @property start where a line's or arrow's first end is attached, or null.
 * @property end where its other end (an arrow's tip) is attached, or null.
 */
data class ShapeInfo(
    val id: String,
    val kind: String,
    val points: List<Float>,
    val start: ShapeLink? = null,
    val end: ShapeLink? = null,
) {
    init {
        require(points.size % 2 == 0) { "Shape points come in x, y pairs" }
    }

    val pointCount: Int get() = points.size / 2
}

/**
 * A connector end glued to shape [targetId]. [at] is a place on the target's outline that survives moving,
 * resizing and turning it: on a polygon (or a line) the edge index plus how far along it (2.5 = the middle of
 * the third edge); on an ellipse a quarter-turn count round it from the first semi-axis (0..4). Negative = the center.
 */
data class ShapeLink(val targetId: String, val at: Float) {
    val isCenter: Boolean get() = at < 0f
}
