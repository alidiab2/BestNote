package app.bestnote.feature.editor.text

import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextElement
import kotlin.math.cos
import kotlin.math.sin

/** Keeps the caret visible above the soft keyboard and the format bar (TEXT-04). Pure: view px in, px out. */
internal object CaretViewport {
    /**
     * How far to scroll the pages down (positive) or up (negative) so the caret line [caretTop]..[caretBottom] sits
     * inside [visibleTop]..[visibleBottom] with [margin] to spare; 0 when it already does. When the room is smaller
     * than the line, the line's bottom wins (what is being typed stays readable above the keyboard).
     */
    fun scrollFor(caretTop: Float, caretBottom: Float, visibleTop: Float, visibleBottom: Float, margin: Float): Int {
        val below = caretBottom + margin - visibleBottom
        if (below > 0f) return kotlin.math.ceil(below).toInt()
        val above = visibleTop - (caretTop - margin)
        if (above > 0f) return -kotlin.math.ceil(above).toInt()
        return 0
    }
}

/**
 * A text box started by a tap grows with its words (TEXT-04): it is as wide as its longest line, from the tap toward
 * where the text runs (rightward for English, leftward for Arabic), until it meets the page margin; then it wraps and
 * grows in height. Pure.
 */
internal object AutoGrow {
    /**
     * The box's left edge and width in page points. [anchorX] is where the tap was; [contentWidth] the widest line
     * unwrapped; [minWidth] keeps a just-started box tappable. Near the far margin the box shifts back so it keeps
     * at least [minWidth] of room.
     */
    fun frame(anchorX: Float, pageWidth: Float, margin: Float, contentWidth: Float, minWidth: Float, rtl: Boolean): Pair<Float, Float> {
        val usable = (pageWidth - 2 * margin).coerceAtLeast(1f)
        val min = minWidth.coerceAtMost(usable)
        val x = anchorX.coerceIn(margin, pageWidth - margin)
        return if (!rtl) {
            val room = (pageWidth - margin - x).coerceAtLeast(min)
            val left = minOf(x, pageWidth - margin - room)
            left to contentWidth.coerceIn(min, room)
        } else {
            val room = (x - margin).coerceAtLeast(min)
            val right = maxOf(x, margin + room)
            val w = contentWidth.coerceIn(min, room)
            (right - w) to w
        }
    }
}

/** Which text box a tap lands on. Pure. */
internal object TextHit {
    /** The topmost unlocked text box on a page whose frame (grown by [slop] points) holds page point ([x], [y]). */
    fun at(elements: List<PageElement>, x: Float, y: Float, slop: Float = 0f): TextElement? =
        elements.asReversed().firstOrNull { it is TextElement && !it.locked && contains(it, x, y, slop) } as TextElement?

    fun contains(e: PageElement, x: Float, y: Float, slop: Float = 0f): Boolean {
        val dx = x - e.cx
        val dy = y - e.cy
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        return kotlin.math.abs(lx) <= e.width / 2f + slop && kotlin.math.abs(ly) <= e.height / 2f + slop
    }

    /** Page point ([x], [y]) in [e]'s own frame, measured from its top-left corner (unrotated). */
    fun local(e: PageElement, x: Float, y: Float): Pair<Float, Float> {
        val dx = x - e.cx
        val dy = y - e.cy
        val c = cos(e.rotation)
        val s = sin(e.rotation)
        return (dx * c + dy * s + e.width / 2f) to (-dx * s + dy * c + e.height / 2f)
    }

    /** Where [e]'s top-center lies on the page: a box grows downward from it, so it is the editor's pivot. */
    fun topCenter(e: PageElement): Pair<Float, Float> =
        (e.cx + sin(e.rotation) * e.height / 2f) to (e.cy - cos(e.rotation) * e.height / 2f)
}
