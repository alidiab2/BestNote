package app.bestnote.feature.editor

import app.bestnote.core.model.PaperTemplate

/** Which pages a paper change in the paper sheet applies to (PAGES-10). */
internal enum class PaperScope { ThisPage, AllPages, NewPages }

/** The paper sheet's template groups, in the order they are shown (PAGES-10). */
internal enum class PaperGroup { Basic, Study, Planning, Music }

/**
 * Pure page-manager and paper-sheet logic (PAGES-10): which pages a change touches, and how a
 * multi-page "Move to…" becomes the single-page moves the document records as one undo step.
 */
internal object PageOps {
    /** Every template, grouped for the paper sheet; each template is in exactly one group. */
    val groups: Map<PaperGroup, List<PaperTemplate>> = linkedMapOf(
        PaperGroup.Basic to listOf(
            PaperTemplate.Blank, PaperTemplate.RuledNarrow, PaperTemplate.Ruled,
            PaperTemplate.RuledWide, PaperTemplate.Grid, PaperTemplate.Dotted,
        ),
        PaperGroup.Study to listOf(PaperTemplate.Cornell, PaperTemplate.Graph),
        PaperGroup.Planning to listOf(PaperTemplate.PlannerDaily, PaperTemplate.PlannerWeekly),
        PaperGroup.Music to listOf(PaperTemplate.MusicStaff),
    )

    /** Zero-based pages a paper change with [scope] rewrites now. [PaperScope.NewPages] rewrites none: it sets the paper of pages added later. */
    fun paperTargets(scope: PaperScope, current: Int, count: Int): List<Int> = when (scope) {
        PaperScope.ThisPage -> if (current in 0 until count) listOf(current) else emptyList()
        PaperScope.AllPages -> (0 until count).toList()
        PaperScope.NewPages -> emptyList()
    }

    /**
     * The new order (old indices) after moving the [selected] pages, as a block in their current
     * order, to sit after page [afterPage] (one-based, counted in the current numbering; 0 = the
     * start, `count` = the end). Selected pages never count as the place they move after.
     */
    fun moveBlock(count: Int, selected: Collection<Int>, afterPage: Int): List<Int> {
        val picked = selected.filter { it in 0 until count }.toSortedSet().toList()
        val rest = (0 until count).filter { it !in picked }
        val at = rest.count { it < afterPage.coerceIn(0, count) }
        return rest.take(at) + picked + rest.drop(at)
    }

    /**
     * Single moves (from, to) that turn the order 0..n-1 into [order] when applied one after the
     * other with "remove at from, insert at to" semantics (`PagedDocument.movePage`). Empty when
     * [order] is already in place.
     */
    fun movesFor(order: List<Int>): List<Pair<Int, Int>> {
        val live = order.indices.toMutableList()
        val moves = ArrayList<Pair<Int, Int>>()
        for (target in order.indices) {
            val from = live.indexOf(order[target])
            if (from != target) {
                live.add(target, live.removeAt(from))
                moves += from to target
            }
        }
        return moves
    }

    /** The page index a page inserted in the gap after page [afterIndex] (zero-based; -1 = before the first) gets. */
    fun insertIndex(afterIndex: Int, count: Int): Int = (afterIndex + 1).coerceIn(0, count)

    /** "Select all" toggles: everything selected becomes nothing, anything less becomes everything. */
    fun <T> selectAll(all: List<T>, selected: Set<T>): Set<T> = if (all.isNotEmpty() && selected.containsAll(all)) emptySet() else all.toSet()

    /** Zero-based indices of the [selected] ids in document order. */
    fun <T> indicesOf(ids: List<T>, selected: Set<T>): List<Int> = ids.indices.filter { ids[it] in selected }
}
