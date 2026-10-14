package app.bestnote.engine.layout

import app.bestnote.core.model.LayoutTemplate
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.RectPt

/** Measures how tall a block is at a given width. The canvas supplies a text/LaTeX-aware one. */
fun interface BlockMeasurer {
    fun heightFor(item: FlowItem, width: Float): Float
}

/**
 * A block waiting for placement.
 *
 * @property role where the transcriber wants it (title, main, margin…).
 * @property keepWithNext never strand this block at the bottom of a region without the block
 *   after it. Headings and definitions use it so a topic header never ends a page alone.
 * @property anchorKey for margin notes: the block the remark comments on, so it sits beside it.
 * @property order the logical lecture position the transcriber assigned, if any.
 * @property sourceBox where the content was on the board (0..1 of the photo), for reading order.
 */
data class FlowItem(
    val key: String,
    val role: RegionRole,
    val keepWithNext: Boolean = false,
    val anchorKey: String? = null,
    val order: Int? = null,
    val sourceBox: RectPt? = null,
)

data class Placement(val key: String, val pageIndex: Int, val frame: RectPt)

/**
 * Flows items through the template's regions in the given (logical) order, spilling to the next
 * column or a fresh page when a region fills. Items never overlap and never cross a region edge;
 * an item taller than an empty region is placed anyway (clipped) so nothing is lost.
 *
 * Margin notes are aligned with the top of the block they annotate, like a student writing a
 * remark next to the line it belongs to.
 */
class BlockFlow(
    private val template: LayoutTemplate,
    private val size: PageSize,
    private val measurer: BlockMeasurer,
    private val spacing: Float = 10f,
    private val rtl: Boolean = false,
) {
    fun place(items: List<FlowItem>): List<Placement> {
        val withMargin = items.any { it.role == RegionRole.Margin }
        val regions = TemplateRegions.of(template, size, withMargin, rtl)
        val out = ArrayList<Placement>(items.size)
        val placedByKey = HashMap<String, Placement>()
        // Each routed role keeps its own cursor so cues/margin and main notes fill side by side.
        val cursors = HashMap<RegionRole, Cursor>()

        for ((index, item) in items.withIndex()) {
            val role = routeRole(item.role, regions)
            val candidates = regions.filter { it.role == role }
            val cursor = cursors.getOrPut(role) { Cursor(0, 0, candidates.first().rect.top) }

            item.anchorKey?.let(placedByKey::get)?.let { anchor -> cursor.alignTo(anchor, candidates) }

            var region = candidates[cursor.regionIndex].rect
            val h = measurer.heightFor(item, region.width)
            val next = items.getOrNull(index + 1)?.takeIf { item.keepWithNext && routeRole(it.role, regions) == role }
            val needed = h + (next?.let { spacing + measurer.heightFor(it, region.width) } ?: 0f)

            if (cursor.y + needed > region.bottom && cursor.y > region.top) {
                cursor.advance(candidates)
                region = candidates[cursor.regionIndex].rect
            }
            val placement = Placement(item.key, cursor.page, RectPt(region.left, cursor.y, region.right, cursor.y + h))
            out += placement
            placedByKey[item.key] = placement
            cursor.y += h + spacing
        }
        return out
    }

    /** Templates without a role fall back sensibly (e.g. margin notes on Cornell go to cues). */
    private fun routeRole(role: RegionRole, regions: List<Region>): RegionRole {
        fun has(r: RegionRole) = regions.any { it.role == r }
        return when {
            has(role) -> role
            role == RegionRole.Margin && has(RegionRole.Cues) -> RegionRole.Cues
            role != RegionRole.Title && has(RegionRole.Main) -> RegionRole.Main
            role != RegionRole.Title && has(RegionRole.Column) -> RegionRole.Column
            else -> RegionRole.Title
        }
    }

    private class Cursor(var page: Int, var regionIndex: Int, var y: Float) {
        fun advance(candidates: List<Region>) {
            regionIndex++
            if (regionIndex >= candidates.size) { regionIndex = 0; page++ }
            y = candidates[regionIndex].rect.top
        }

        /** Moves down (never up) so this item starts level with [anchor]. */
        fun alignTo(anchor: Placement, candidates: List<Region>) {
            if (anchor.pageIndex > page) {
                page = anchor.pageIndex
                regionIndex = 0
                y = candidates[0].rect.top
            }
            if (anchor.pageIndex == page) y = maxOf(y, anchor.frame.top)
        }
    }
}
