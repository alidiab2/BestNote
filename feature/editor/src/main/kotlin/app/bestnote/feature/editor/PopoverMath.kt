package app.bestnote.feature.editor

/** The popover edge its caret sits on: the edge that faces the anchor. */
internal enum class CaretEdge { Top, Bottom, Left, Right }

/**
 * Where an anchored popover goes (DS-12): [x], [y] are its top-left in window px, [edge] is the
 * side facing the anchor and [caret] is the caret's center along that edge, in px from the
 * popover's own left (Top/Bottom) or top (Left/Right).
 */
internal data class PopoverPlacement(val x: Int, val y: Int, val edge: CaretEdge, val caret: Int, val maxHeight: Int = Int.MAX_VALUE)

/** A rectangle in window px (left, top, right, bottom). */
internal data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Pure placement math for popovers that point at what opened them (the tapped pen, the colour
 * dot, the width button, a chrome button). The popover opens on the page side of the tray: below
 * a top tray, above a bottom one, beside a side one; it flips only when the preferred side is too
 * short and the other side has more room. It never covers its anchor: when it is taller than the room
 * on its side, [PopoverPlacement.maxHeight] says how tall it may be (its content scrolls). It slides
 * along the edge to stay in the window, and the caret keeps pointing at the anchor.
 */
internal object PopoverMath {
    /**
     * @param gap px between the anchor and the popover's edge (the caret lives in it).
     * @param margin px the popover keeps from the window edges.
     * @param caretInset the caret center stays this far (px) from the popover's corners.
     */
    fun place(
        anchor: PxRect,
        windowWidth: Int,
        windowHeight: Int,
        width: Int,
        height: Int,
        side: TraySide,
        gap: Int,
        margin: Int,
        caretInset: Int,
    ): PopoverPlacement {
        fun clamp(v: Int, size: Int, window: Int) = v.coerceIn(margin, (window - size - margin).coerceAtLeast(margin))
        fun caretAt(center: Int, origin: Int, size: Int) =
            (center - origin).coerceIn(caretInset.coerceAtMost(size / 2), (size - caretInset).coerceAtLeast(size / 2))

        return when (side) {
            TraySide.Top, TraySide.Bottom -> {
                val below = windowHeight - margin - (anchor.bottom + gap)
                val above = anchor.top - gap - margin
                val goBelow = if (side == TraySide.Top) below >= height || below >= above else above < height && below > above
                val x = clamp(anchor.centerX - width / 2, width, windowWidth)
                val room = (if (goBelow) below else above).coerceAtLeast(0)
                val shown = height.coerceAtMost(room)
                val y = if (goBelow) anchor.bottom + gap else anchor.top - gap - shown
                PopoverPlacement(x, y, if (goBelow) CaretEdge.Top else CaretEdge.Bottom, caretAt(anchor.centerX, x, width), maxHeight = room)
            }
            TraySide.Left, TraySide.Right -> {
                val right = windowWidth - margin - (anchor.right + gap)
                val left = anchor.left - gap - margin
                val goRight = if (side == TraySide.Left) right >= width || right >= left else left < width && right > left
                val shown = height.coerceAtMost((windowHeight - 2 * margin).coerceAtLeast(0))
                val y = clamp(anchor.centerY - shown / 2, shown, windowHeight)
                val x = if (goRight) clamp(anchor.right + gap, width, windowWidth) else clamp(anchor.left - gap - width, width, windowWidth)
                PopoverPlacement(x, y, if (goRight) CaretEdge.Left else CaretEdge.Right, caretAt(anchor.centerY, y, shown), maxHeight = windowHeight - 2 * margin)
            }
        }
    }
}
