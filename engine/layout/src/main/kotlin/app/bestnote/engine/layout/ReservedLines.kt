package app.bestnote.engine.layout

import app.bestnote.core.model.RectPt
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The ruled lines the user already wrote on, per page, so a Smart Board re-layout (a follow-up
 * photo) places its blocks around them ([RuledFlow] `reserved`). Encoded as "page:line,line;…" for
 * navigation arguments.
 */
data class ReservedLines(val byPage: Map<Int, Set<Int>> = emptyMap()) {
    fun contains(page: Int, line: Int): Boolean = byPage[page]?.contains(line) == true

    val isEmpty: Boolean get() = byPage.values.all { it.isEmpty() }

    fun encode(): String = byPage.filterValues { it.isNotEmpty() }.toSortedMap().entries.joinToString(";") { (p, lines) -> "$p:" + lines.sorted().joinToString(",") }

    companion object {
        /** Lines of [paper] that the boxes ([boxes], page points) touch: a line is the band from the line above down to it. */
        fun of(paper: RuledPaper, boxes: List<RectPt>): Set<Int> {
            val out = HashSet<Int>()
            for (b in boxes) {
                if (b.isEmpty && b.width <= 0f) continue
                // Band of line i: (lineY(i) - gap, lineY(i) + small descent].
                val first = ceil((b.top - paper.firstLine) / paper.lineGap - 0.15f).toInt().coerceAtLeast(0)
                val last = floor((b.bottom - paper.firstLine) / paper.lineGap + 0.85f).toInt().coerceAtMost(paper.lineCount - 1)
                for (i in first..last) out += i
            }
            return out
        }

        fun decode(s: String?): ReservedLines {
            if (s.isNullOrBlank()) return ReservedLines()
            val map = HashMap<Int, Set<Int>>()
            for (part in s.split(';')) {
                val page = part.substringBefore(':').toIntOrNull() ?: continue
                map[page] = part.substringAfter(':', "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            }
            return ReservedLines(map)
        }
    }
}
