package app.bestnote.feature.editor

import android.content.Context
import app.bestnote.engine.canvas.document.ShapeGhostText
import app.bestnote.engine.canvas.shape.ShapeHint
import app.bestnote.engine.canvas.shape.ShapeLabel

/** CANVAS-17: the translated words on the hold-to-shape ghost's label. */
internal fun shapeGhostText(context: Context): ShapeGhostText {
    val names = mapOf(
        ShapeLabel.Line to R.string.shape_line,
        ShapeLabel.Arrow to R.string.shape_arrow,
        ShapeLabel.Circle to R.string.shape_circle,
        ShapeLabel.Ellipse to R.string.shape_ellipse,
        ShapeLabel.Rectangle to R.string.shape_rectangle,
        ShapeLabel.Square to R.string.shape_square,
        ShapeLabel.Triangle to R.string.shape_triangle,
        ShapeLabel.Polygon to R.string.shape_polygon,
        ShapeLabel.Diamond to R.string.shape_diamond,
        ShapeLabel.Star to R.string.shape_star,
        ShapeLabel.Polyline to R.string.shape_polyline,
    )
    val hints = mapOf(
        ShapeHint.PerfectCircle to R.string.shape_hint_perfect,
        ShapeHint.RightAngles to R.string.shape_hint_right_angles,
        ShapeHint.Horizontal to R.string.shape_hint_horizontal,
        ShapeHint.Vertical to R.string.shape_hint_vertical,
        ShapeHint.Diagonal to R.string.shape_hint_diagonal,
        ShapeHint.Glued to R.string.shape_hint_glued,
    )
    return ShapeGhostText(names.mapValues { context.getString(it.value) }, hints.mapValues { context.getString(it.value) })
}
