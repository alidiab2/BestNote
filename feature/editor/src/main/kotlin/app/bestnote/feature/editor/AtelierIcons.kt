package app.bestnote.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The editor chrome's line icons, taken 1:1 from the approved Atelier editor mock (24 x 24, ~1.8 stroke).
 * Tinted by `Icon` like any other vector. Direction-bound icons auto-mirror in RTL.
 */
internal object AtelierIcons {
    private fun icon(name: String, mirror: Boolean = false, stroke: Float = 1.8f, vararg paths: String, filled: List<String> = emptyList()) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            filled.forEach { addPath(pathData = addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Back = icon("back", mirror = true, stroke = 2.1f, "M15 5l-7 7 7 7")
    val Undo = icon("undo", mirror = true, stroke = 1.9f, "M8.5 6.5L4 11l4.5 4.5", "M4.5 11H14a4.5 4.5 0 0 1 0 9h-3")
    val Redo = icon("redo", mirror = true, stroke = 1.9f, "M15.5 6.5L20 11l-4.5 4.5", "M19.5 11H10a4.5 4.5 0 0 0 0 9h3")
    val Sparkle = icon(
        "sparkle",
        filled = listOf("M11 3l1.8 5.2L18 10l-5.2 1.8L11 17l-1.8-5.2L4 10l5.2-1.8z", "M18.5 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"),
    )
    val Pages = icon("pages", false, 1.8f, "M9 3.5h8a2 2 0 0 1 2 2v11a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2z", "M5 7v11.5a2 2 0 0 0 2 2h9")
    val Layers = icon("layers", false, 1.8f, "M12 4l8.5 4.5L12 13 3.5 8.5z", "M3.5 12.5L12 17l8.5-4.5M3.5 16.5L12 21l8.5-4.5")
    val Export = icon("export", false, 1.8f, "M12 4v11M7.5 8.5L12 4l4.5 4.5", "M5 13v5.5a1.5 1.5 0 0 0 1.5 1.5h11a1.5 1.5 0 0 0 1.5-1.5V13")
    val More = icon(
        "more",
        filled = listOf(
            "M3.9 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z",
            "M10.4 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z",
            "M16.9 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z",
        ),
    )
    val Lasso = icon(
        "lasso",
        false,
        1.8f,
        "M20.4 11.0A8 5.5 0 0 1 17.9 14.1", "M15.2 15.2A8 5.5 0 0 1 10.0 15.2", "M4.6 9.0A8 5.5 0 0 1 7.1 5.9",
        "M9.8 4.8A8 5.5 0 0 1 15.0 4.8", "M17.6 5.8A8 5.5 0 0 1 20.3 8.9", "M7 14.5c-1.5 2 0 4.5 2.5 4",
    )
    val Shapes = icon("shapes", false, 1.8f, "M3.5 8a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0z", "M12.5 11h6.5a1.5 1.5 0 0 1 1.5 1.5v6.5a1.5 1.5 0 0 1 -1.5 1.5h-6.5a1.5 1.5 0 0 1 -1.5 -1.5v-6.5a1.5 1.5 0 0 1 1.5 -1.5z")
    val Text = icon("text", false, 1.9f, "M5 6.5V5h14v1.5M12 5v14M9.5 19h5")
    val Image = icon("image", false, 1.8f, "M6 5h12a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1 -2.5 2.5h-12a2.5 2.5 0 0 1 -2.5 -2.5v-9a2.5 2.5 0 0 1 2.5 -2.5z", "M7.4 10a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0z", "M4 17l5-4.5 4 3.5 3-2.5 4 3.5")
    val Mic = icon("mic", false, 1.8f, "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1 -6 0v-5a3 3 0 0 1 3 -3z", "M5.5 11.5a6.5 6.5 0 0 0 13 0", "M12 18v2.5")
    val Ruler = icon("ruler", false, 1.8f, "M3.8 15.6L15.6 3.8l4.6 4.6L8.4 20.2z", "M7.5 12l1.8 1.8M10 9.5l1.2 1.2M12.5 7l1.8 1.8")
    val Collapse = icon("collapse", false, 2f, "M7 14l5-5 5 5")
    val Plus = icon("plus", false, 2f, "M12 6v12M6 12h12")
    val Minus = icon("minus", false, 2f, "M6 12h12")
    val Check = icon("check", false, 2.2f, "M5.5 12.5l4 4 9-9")
    val Trash = icon("trash", false, 1.8f, "M4.5 7h15M9.5 7V5h5v2M6.5 7l1 12.5h9l1-12.5")
}
