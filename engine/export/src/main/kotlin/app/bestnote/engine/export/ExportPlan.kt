package app.bestnote.engine.export

/** Which pages an export covers. */
sealed interface PageRange {
    data object All : PageRange
    data class Single(val index: Int) : PageRange

    /** Zero-based page indices, ascending and without duplicates. */
    data class Pages(val indices: List<Int>) : PageRange
}

/** What an export draws besides the notebook's own content. */
data class ExportOptions(
    /** Draw each page's ruling / grid / dots. Imported PDF pages always keep their own content. */
    val includeTemplate: Boolean = true,
)

/** Result of reading a "1-3, 5, 8-" style page range. */
sealed interface RangeParse {
    data class Ok(val indices: List<Int>) : RangeParse
    data class Invalid(val token: String) : RangeParse
}

/** Pure helpers that turn the user's choices into the pages to export. */
object ExportPlan {
    /** The zero-based pages [range] covers in a notebook of [pageCount] pages, ascending. */
    fun select(range: PageRange, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        return when (range) {
            PageRange.All -> (0 until pageCount).toList()
            is PageRange.Single -> listOf(range.index.coerceIn(0, pageCount - 1))
            is PageRange.Pages -> range.indices.filter { it in 0 until pageCount }.distinct().sorted()
        }
    }

    /**
     * Reads one-based page numbers: `5`, `2-4`, `8-` (to the last page), separated by commas,
     * semicolons or spaces. Arabic-Indic digits and the Arabic comma work. Anything out of range,
     * reversed or unreadable is [RangeParse.Invalid] with the offending token.
     */
    fun parseRange(text: String, pageCount: Int): RangeParse {
        val tokens = text.replace('،', ',').split(',', ';', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return RangeParse.Invalid(text.trim())
        val out = sortedSetOf<Int>()
        for (token in tokens) {
            val parts = token.split('-', '–', '—')
            if (parts.size > 2) return RangeParse.Invalid(token)
            val from = if (parts[0].isEmpty()) return RangeParse.Invalid(token) else number(parts[0]) ?: return RangeParse.Invalid(token)
            val to = when {
                parts.size == 1 -> from
                parts[1].isEmpty() -> pageCount
                else -> number(parts[1]) ?: return RangeParse.Invalid(token)
            }
            if (from < 1 || to > pageCount || from > to) return RangeParse.Invalid(token)
            for (n in from..to) out += n - 1
        }
        return RangeParse.Ok(out.toList())
    }

    /**
     * The one-based range text [parseRange] reads back as exactly [indices] (zero-based): runs of
     * three or more pages collapse to `a-b`, e.g. [0, 1, 2, 4] → "1-3, 5". Pages chosen in the page
     * manager (PAGES-10) prefill the export dialog with it.
     */
    fun formatRange(indices: Collection<Int>): String {
        val sorted = indices.filter { it >= 0 }.toSortedSet().toList()
        val parts = ArrayList<String>()
        var i = 0
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            when {
                j - i >= 2 -> parts += "${sorted[i] + 1}-${sorted[j] + 1}"
                else -> for (k in i..j) parts += "${sorted[k] + 1}"
            }
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    private fun number(s: String): Int? {
        if (s.isEmpty() || s.length > 7) return null
        var n = 0
        for (c in s) {
            val d = Character.digit(c, 10)
            if (d < 0) return null
            n = n * 10 + d
        }
        return n
    }
}

/**
 * Where to put an overlay (our page drawn in a `w x h` points box, y-down at the top-left) onto an
 * existing PDF page that has /Rotate [rotation] and a visible box [cropWidth] x [cropHeight] whose
 * lower-left corner is ([cropX], [cropY]). Returns the affine matrix `a b c d e f` (PDF order) that
 * maps overlay form space (y-up, as the form is stored) into that page's user space.
 */
internal object OverlayTransform {
    fun of(rotation: Int, cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float, w: Float, h: Float): FloatArray {
        val visibleW = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
        val visibleH = if (rotation == 90 || rotation == 270) cropWidth else cropHeight
        val sx = visibleW / w
        val sy = visibleH / h
        // (u, v) in the rotated, scaled page -> unrotated crop space (x, y)
        val m = when (((rotation % 360) + 360) % 360) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, cropWidth, 0f)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, cropWidth, cropHeight)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, cropHeight)
            else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        }
        // Compose: first scale (x*sx, y*sy), then m, then the crop offset.
        return floatArrayOf(m[0] * sx, m[1] * sx, m[2] * sy, m[3] * sy, m[4] + cropX, m[5] + cropY)
    }
}
