package app.bestnote.engine.layout.paper

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import kotlin.math.floor

/** What a template line is for; the renderer picks its color from the paper. */
enum class LineRole {
    /** Writing line of ruled paper. */
    Rule,

    /** The red vertical margin line. */
    Margin,

    /** Grid paper line. */
    Grid,

    /** Section divider (Cornell cue column, summary area, title band). */
    Divider,

    /** One of the five lines of a music staff. */
    Staff,

    /** Every fifth line of graph paper, drawn heavier than the [Grid] lines between. */
    GridMajor,
}

/** A straight template line in page points. Lines are always horizontal or vertical. */
data class TemplateLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val role: LineRole) {
    val horizontal: Boolean get() = y0 == y1
}

/** Dots at `left + i * spacing, top + j * spacing` for `i < cols, j < rows`, in page points. */
data class DotGrid(val left: Float, val top: Float, val spacing: Float, val cols: Int, val rows: Int) {
    val count: Int get() = cols * rows
}

/** Everything a template prints on one page, in page points (origin top-left). */
data class TemplateGeometry(val lines: List<TemplateLine>, val dots: DotGrid? = null) {
    companion object {
        val Empty = TemplateGeometry(emptyList())
    }
}

/**
 * Template geometry for PAGES-01. All measures are physical (points), so a ruled A5 page has the
 * same line height as a ruled A4 page; margins shrink on small pages. Pure: the canvas converts to
 * pixels with [PixelSnap] so every line lands on exactly one physical pixel at any zoom.
 */
object PaperGeometry {
    const val MM = 72f / 25.4f

    /** Line heights: narrow 6.35 mm, college 7.1 mm, wide 8.7 mm. */
    const val NARROW = 6.35f * MM
    const val COLLEGE = 7.1f * MM
    const val WIDE = 8.7f * MM

    /** Grid and dot pitch: 5 mm. */
    const val GRID = 5f * MM

    /** Music: 5 lines 2 mm apart (an 8 mm staff), at least 14 mm between staves. */
    const val STAFF_LINE_GAP = 2f * MM
    const val STAFF_MIN_GAP = 14f * MM
    const val STAFF_SIDE = 12f * MM
    const val STAFF_TOP = 15f * MM

    fun of(template: PaperTemplate, size: PageSize): TemplateGeometry = when (template) {
        PaperTemplate.Blank -> TemplateGeometry.Empty
        PaperTemplate.RuledNarrow -> ruled(size, NARROW)
        PaperTemplate.Ruled -> ruled(size, COLLEGE)
        PaperTemplate.RuledWide -> ruled(size, WIDE)
        PaperTemplate.Grid -> grid(size)
        PaperTemplate.Dotted -> dotted(size)
        PaperTemplate.Cornell -> cornell(size)
        PaperTemplate.MusicStaff -> music(size)
        PaperTemplate.PlannerDaily -> plannerDaily(size)
        PaperTemplate.PlannerWeekly -> plannerWeekly(size)
        PaperTemplate.Graph -> graph(size)
    }

    /** First writing line: about 1 in on a full-size page, never less than two line heights. */
    fun headerHeight(size: PageSize, spacing: Float): Float = (size.heightPt * 0.086f).coerceIn(2f * spacing, 72f)

    /** Margin line x: 1.25 in on a full-size page, less on small pages. */
    fun marginX(size: PageSize): Float = (size.widthPt * 0.13f).coerceIn(40f, 90f)

    private fun ruled(size: PageSize, spacing: Float): TemplateGeometry {
        val lines = ArrayList<TemplateLine>()
        rules(size, headerHeight(size, spacing), size.heightPt - spacing, spacing, 0f, size.widthPt, lines)
        lines += TemplateLine(marginX(size), 0f, marginX(size), size.heightPt, LineRole.Margin)
        return TemplateGeometry(lines)
    }

    private fun rules(size: PageSize, from: Float, to: Float, spacing: Float, x0: Float, x1: Float, out: MutableList<TemplateLine>) {
        if (size.widthPt <= 0f) return
        var y = from
        while (y <= to + 0.01f) {
            out += TemplateLine(x0, y, x1, y, LineRole.Rule)
            y += spacing
        }
    }

    /** First grid line (or dot row) of a [pitch] lattice on a side of [length], as the editor prints it. */
    fun latticeStart(length: Float, pitch: Float): Float = centered(length, pitch)

    /** Offset that centers a [pitch] lattice on a side of [length], so both edges get the same remainder. */
    private fun centered(length: Float, pitch: Float): Float {
        val rem = length - floor(length / pitch) * pitch
        return if (rem < pitch * 0.5f) (rem + pitch) / 2f else rem / 2f
    }

    private fun grid(size: PageSize): TemplateGeometry {
        val lines = ArrayList<TemplateLine>()
        var x = centered(size.widthPt, GRID)
        while (x < size.widthPt) {
            lines += TemplateLine(x, 0f, x, size.heightPt, LineRole.Grid)
            x += GRID
        }
        var y = centered(size.heightPt, GRID)
        while (y < size.heightPt) {
            lines += TemplateLine(0f, y, size.widthPt, y, LineRole.Grid)
            y += GRID
        }
        return TemplateGeometry(lines)
    }

    private fun dotted(size: PageSize): TemplateGeometry {
        val left = centered(size.widthPt, GRID)
        val top = centered(size.heightPt, GRID)
        val cols = floor((size.widthPt - left) / GRID).toInt() + 1
        val rows = floor((size.heightPt - top) / GRID).toInt() + 1
        // Drop a last column/row that would sit on the edge.
        val c = if (left + (cols - 1) * GRID > size.widthPt - GRID / 2) cols - 1 else cols
        val r = if (top + (rows - 1) * GRID > size.heightPt - GRID / 2) rows - 1 else rows
        return TemplateGeometry(emptyList(), DotGrid(left, top, GRID, c.coerceAtLeast(0), r.coerceAtLeast(0)))
    }

    /** Cornell: title band, cue column (30 %) beside the notes, summary area (bottom 20 %). */
    fun cornellSummaryTop(size: PageSize): Float = size.heightPt * 0.8f

    fun cornellCueX(size: PageSize): Float = size.widthPt * 0.3f

    private fun cornell(size: PageSize): TemplateGeometry {
        val spacing = COLLEGE
        val top = headerHeight(size, spacing)
        val summary = cornellSummaryTop(size)
        val cue = cornellCueX(size)
        val lines = ArrayList<TemplateLine>()
        rules(size, top + spacing, summary - spacing * 0.5f, spacing, 0f, size.widthPt, lines)
        rules(size, summary + spacing, size.heightPt - spacing, spacing, 0f, size.widthPt, lines)
        lines += TemplateLine(0f, top, size.widthPt, top, LineRole.Divider)
        lines += TemplateLine(cue, top, cue, summary, LineRole.Divider)
        lines += TemplateLine(0f, summary, size.widthPt, summary, LineRole.Divider)
        return TemplateGeometry(lines)
    }

    /** Graph paper pitch: 2 mm squares, a heavier line every 5th (1 cm). */
    const val GRAPH = 2f * MM
    const val GRAPH_MAJOR_EVERY = 5

    /** Daily planner: hours listed in the left schedule, tasks on the right. */
    const val PLANNER_FIRST_HOUR = 6
    const val PLANNER_HOURS = 16
    const val PLANNER_TIME_COLUMN = 14f * MM
    const val PLANNER_BOTTOM = 10f * MM

    /** Weekly planner: two columns of day boxes, four rows (Mon-Sat, then Sunday + notes). */
    const val WEEK_COLS = 2
    const val WEEK_ROWS = 4

    /** x of the divider between the daily planner's schedule and its task list. */
    fun plannerSplitX(size: PageSize): Float = size.widthPt * 0.62f

    /** Height of one hour row of the daily planner. */
    fun plannerHourHeight(size: PageSize): Float =
        (size.heightPt - headerHeight(size, COLLEGE) - PLANNER_BOTTOM) / PLANNER_HOURS

    private fun plannerDaily(size: PageSize): TemplateGeometry {
        val w = size.widthPt
        val h = size.heightPt
        val top = headerHeight(size, COLLEGE)
        val bottom = h - PLANNER_BOTTOM
        val split = plannerSplitX(size)
        val timeX = PLANNER_TIME_COLUMN.coerceAtMost(split / 3f)
        val row = plannerHourHeight(size)
        val lines = ArrayList<TemplateLine>()
        lines += TemplateLine(0f, top, w, top, LineRole.Divider)
        for (i in 1..PLANNER_HOURS) {
            val y = top + i * row
            lines += TemplateLine(0f, y, split, y, if (i == PLANNER_HOURS) LineRole.Divider else LineRole.Rule)
        }
        lines += TemplateLine(timeX, top, timeX, bottom, LineRole.Divider)
        lines += TemplateLine(split, top, split, bottom, LineRole.Divider)
        // Task list: college rules right of the split.
        var y = top + COLLEGE
        while (y <= bottom + 0.01f) {
            lines += TemplateLine(split, y, w, y, LineRole.Rule)
            y += COLLEGE
        }
        return TemplateGeometry(lines)
    }

    /** Top-left corner and size of weekly planner box [index] (0..7, row-major, 2 columns). */
    fun weekBox(size: PageSize, index: Int): FloatArray {
        val top = headerHeight(size, COLLEGE)
        val bottom = size.heightPt - PLANNER_BOTTOM
        val cw = size.widthPt / WEEK_COLS
        val ch = (bottom - top) / WEEK_ROWS
        return floatArrayOf((index % WEEK_COLS) * cw, top + (index / WEEK_COLS) * ch, cw, ch)
    }

    private fun plannerWeekly(size: PageSize): TemplateGeometry {
        val w = size.widthPt
        val top = headerHeight(size, COLLEGE)
        val bottom = size.heightPt - PLANNER_BOTTOM
        val lines = ArrayList<TemplateLine>()
        lines += TemplateLine(0f, top, w, top, LineRole.Divider)
        for (r in 1..WEEK_ROWS) {
            val y = weekBox(size, (r - 1) * WEEK_COLS)[1] + weekBox(size, 0)[3]
            lines += TemplateLine(0f, y, w, y, LineRole.Divider)
        }
        lines += TemplateLine(w / WEEK_COLS, top, w / WEEK_COLS, bottom, LineRole.Divider)
        // Writing rules inside each box, under a one-line day heading.
        for (i in 0 until WEEK_COLS * WEEK_ROWS) {
            val (x, y0, bw, bh) = weekBox(size, i).toList()
            var y = y0 + COLLEGE * 1.8f
            while (y < y0 + bh - 0.5f) {
                lines += TemplateLine(x + 6f, y, x + bw - 6f, y, LineRole.Rule)
                y += COLLEGE
            }
        }
        return TemplateGeometry(lines)
    }

    private fun graph(size: PageSize): TemplateGeometry {
        val lines = ArrayList<TemplateLine>()
        val x0 = centered(size.widthPt, GRAPH)
        val y0 = centered(size.heightPt, GRAPH)
        var i = 0
        var x = x0
        while (x < size.widthPt) {
            lines += TemplateLine(x, 0f, x, size.heightPt, if (i % GRAPH_MAJOR_EVERY == 0) LineRole.GridMajor else LineRole.Grid)
            x += GRAPH
            i++
        }
        i = 0
        var y = y0
        while (y < size.heightPt) {
            lines += TemplateLine(0f, y, size.widthPt, y, if (i % GRAPH_MAJOR_EVERY == 0) LineRole.GridMajor else LineRole.Grid)
            y += GRAPH
            i++
        }
        return TemplateGeometry(lines)
    }

    /** Number of staves that fit, spread evenly between the top and bottom margins. */
    fun staffCount(size: PageSize): Int {
        val staff = STAFF_LINE_GAP * 4
        val avail = size.heightPt - 2 * STAFF_TOP
        return floor((avail + STAFF_MIN_GAP) / (staff + STAFF_MIN_GAP)).toInt().coerceAtLeast(0)
    }

    private fun music(size: PageSize): TemplateGeometry {
        val n = staffCount(size)
        if (n == 0) return TemplateGeometry.Empty
        val staff = STAFF_LINE_GAP * 4
        val avail = size.heightPt - 2 * STAFF_TOP
        val gap = if (n > 1) (avail - n * staff) / (n - 1) else 0f
        val lines = ArrayList<TemplateLine>(n * 5)
        for (s in 0 until n) {
            val top = STAFF_TOP + s * (staff + gap)
            for (l in 0 until 5) {
                val y = top + l * STAFF_LINE_GAP
                lines += TemplateLine(STAFF_SIDE, y, size.widthPt - STAFF_SIDE, y, LineRole.Staff)
            }
        }
        return TemplateGeometry(lines)
    }
}

/**
 * Maps page points to device pixels so hairlines stay crisp: a 1 px line drawn at a pixel center
 * (n + 0.5) covers exactly one physical pixel row/column, at any zoom.
 */
object PixelSnap {
    /** Pixel-center coordinate for [pt] at [scale] px/pt, relative to an integer-aligned page origin. */
    fun center(pt: Float, scale: Float): Float = floor(pt * scale) + 0.5f

    /** Pixel-center coordinate clamped inside a page [extentPx] wide, so edge lines are never clipped. */
    fun centerIn(pt: Float, scale: Float, extentPx: Int): Float =
        center(pt, scale).coerceIn(0.5f, (extentPx - 0.5f).coerceAtLeast(0.5f))
}
