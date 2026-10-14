package app.bestnote.engine.layout

import app.bestnote.core.model.LayoutTemplate
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.RectPt

/** Semantic slot a reconstructed block is routed to. */
enum class RegionRole { Title, Cues, Main, Summary, Column, Margin }

data class Region(val role: RegionRole, val rect: RectPt)

/**
 * Page geometry for each [LayoutTemplate]. All sizes derive from the page size so templates work
 * on A4, Letter and custom pages alike.
 *
 * [withMargin] reserves a narrow side column for the student's margin notes. It sits on the
 * leading side, where a notebook's margin line is: left for LTR, right for RTL ([rtl]). Cornell
 * already has a cue column, so margin notes go there and no extra column is added.
 */
object TemplateRegions {
    const val MARGIN = 36f
    private const val GUTTER = 14f
    private const val MARGIN_COLUMN = 0.24f

    fun of(template: LayoutTemplate, size: PageSize, withMargin: Boolean = false, rtl: Boolean = false): List<Region> {
        val page = RectPt(0f, 0f, size.widthPt, size.heightPt).inset(MARGIN)
        val titleH = 56f
        val title = Region(RegionRole.Title, RectPt(page.left, page.top, page.right, page.top + titleH))
        val body = RectPt(page.left, title.rect.bottom + GUTTER, page.right, page.bottom)
        return when (template) {
            LayoutTemplate.Cornell -> {
                val summaryTop = body.bottom - body.height * 0.2f
                val (cues, main) = split(RectPt(body.left, body.top, body.right, summaryTop - GUTTER), 0.3f, rtl)
                listOf(
                    title,
                    Region(RegionRole.Cues, cues),
                    Region(RegionRole.Main, main),
                    Region(RegionRole.Summary, RectPt(body.left, summaryTop, body.right, body.bottom)),
                )
            }
            LayoutTemplate.LectureSummary -> {
                val summaryBottom = body.top + body.height * 0.22f
                val summary = Region(RegionRole.Summary, RectPt(body.left, body.top, body.right, summaryBottom))
                val rest = RectPt(body.left, summaryBottom + GUTTER, body.right, body.bottom)
                listOf(title, summary) + mainWithMargin(rest, withMargin, rtl)
            }
            LayoutTemplate.CheatSheet -> {
                // Dense: smaller title band, three columns. Margin notes flow into the columns.
                val t = Region(RegionRole.Title, RectPt(page.left, page.top, page.right, page.top + 32f))
                val top = t.rect.bottom + GUTTER
                val colW = (page.width - 2 * GUTTER) / 3f
                listOf(t) + List(3) { i ->
                    // Columns are listed in reading order, so RTL fills the right column first.
                    val slot = if (rtl) 2 - i else i
                    val l = page.left + slot * (colW + GUTTER)
                    Region(RegionRole.Column, RectPt(l, top, l + colW, page.bottom))
                }
            }
            LayoutTemplate.Minimalist -> listOf(title) + mainWithMargin(body, withMargin, rtl)
        }
    }

    private fun mainWithMargin(area: RectPt, withMargin: Boolean, rtl: Boolean): List<Region> {
        if (!withMargin) return listOf(Region(RegionRole.Main, area))
        val (margin, main) = split(area, MARGIN_COLUMN, rtl)
        return listOf(Region(RegionRole.Main, main), Region(RegionRole.Margin, margin))
    }

    /** Splits [r] into a leading column of [fraction] width and the rest, separated by a gutter. */
    private fun split(r: RectPt, fraction: Float, rtl: Boolean): Pair<RectPt, RectPt> {
        val w = r.width * fraction
        return if (!rtl) {
            RectPt(r.left, r.top, r.left + w, r.bottom) to RectPt(r.left + w + GUTTER, r.top, r.right, r.bottom)
        } else {
            RectPt(r.right - w, r.top, r.right, r.bottom) to RectPt(r.left, r.top, r.right - w - GUTTER, r.bottom)
        }
    }
}
