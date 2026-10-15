package app.bestnote.engine.stylus

import app.bestnote.core.model.Tool
import kotlin.math.max

/**
 * What the hover cursor looks like (SPEN-03), in pixels. Pure, so it is unit-tested on the JVM.
 *
 * Pen-like tools: a thin ring ([RING_STROKE_DP], [RING_RADIUS_DP], grown to fit very wide
 * brushes) with a faint fill and a center dot at least [DOT_DP] wide that is otherwise exactly the
 * brush's width, all in the pen color. Eraser: a neutral outline of the real erase radius.
 */
data class HoverCursorStyle(
    val ringRadius: Float,
    val ringStroke: Float,
    /** 0 = no dot. */
    val dotRadius: Float,
    val colorArgb: Int,
    val fillArgb: Int,
    val erasing: Boolean,
) {
    companion object {
        const val RING_RADIUS_DP = 12f
        const val RING_STROKE_DP = 1.2f
        const val DOT_DP = 2.4f
        const val FILL_ALPHA = 0x1F // ~12%, as in the design board
        const val ERASER_ARGB = 0xFF5F6368.toInt()

        /**
         * @param brushWidthPx stroke width the brush lays down at full pressure.
         * @param eraseRadiusPx hit radius of the eraser.
         */
        fun of(tool: Tool, colorArgb: Int, brushWidthPx: Float, eraseRadiusPx: Float, density: Float): HoverCursorStyle {
            val stroke = RING_STROKE_DP * density
            if (tool == Tool.Eraser) {
                return HoverCursorStyle(eraseRadiusPx, stroke, 0f, ERASER_ARGB, withAlpha(ERASER_ARGB, FILL_ALPHA), erasing = true)
            }
            val opaque = withAlpha(colorArgb, 0xFF)
            val dot = max(DOT_DP * density, brushWidthPx) / 2f
            val ring = max(RING_RADIUS_DP * density, dot + 2 * stroke)
            return HoverCursorStyle(ring, stroke, dot, opaque, withAlpha(colorArgb, FILL_ALPHA), erasing = false)
        }

        private fun withAlpha(argb: Int, alpha: Int) = (argb and 0x00FFFFFF) or (alpha shl 24)
    }
}

/**
 * Where the hover cursor is, if visible. Feed it every [StylusEvent] (hover and touch).
 *
 * Shows for stylus / eraser-end hover; hides on HOVER_EXIT, as soon as the pen touches, and when
 * no hover sample arrived for [STALE_MS] (a missed HOVER_EXIT must not leave a ghost cursor).
 */
class HoverTracker {
    data class Position(val x: Float, val y: Float, val pointer: PointerTool, val buttonPressed: Boolean)

    var position: Position? = null
        private set
    private var lastAt = 0L

    /** Returns true when [position] changed. */
    fun onEvent(event: StylusEvent): Boolean {
        val before = position
        position = when {
            !event.isHover -> null
            event.action == StylusAction.HoverExit -> null
            event.tool != PointerTool.Stylus && event.tool != PointerTool.Eraser -> null
            else -> {
                lastAt = event.latest.timeMillis
                Position(event.latest.x, event.latest.y, event.tool, event.primaryButtonPressed)
            }
        }
        return position != before
    }

    /** Hide if the last hover sample is older than [STALE_MS] at [now]. Returns true when hidden now. */
    fun expire(now: Long): Boolean {
        if (position == null || now - lastAt < STALE_MS) return false
        position = null
        return true
    }

    fun hide() {
        position = null
    }

    companion object {
        const val STALE_MS = 1_500L
    }
}
