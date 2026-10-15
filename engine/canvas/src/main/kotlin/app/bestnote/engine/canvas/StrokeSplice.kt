package app.bestnote.engine.canvas

import java.util.Collections
import java.util.IdentityHashMap

/**
 * An exact, order-preserving change of a page's stroke list: [removed] strokes with their indices
 * in the list before, [added] strokes with their indices in the list after. Undoing it puts every
 * stroke back at its old z-position (an erased stroke under a highlighter stays under it). Strokes
 * compare by identity. Pure Kotlin.
 */
class StrokeSplice<S : Any>(val removed: List<IndexedValue<S>>, val added: List<IndexedValue<S>>) {
    val isEmpty: Boolean get() = removed.isEmpty() && added.isEmpty()

    fun apply(list: MutableList<S>) = change(list, removed, added)

    fun revert(list: MutableList<S>) = change(list, added, removed)

    companion object {
        /** The splice that turns [before] into [after], or null when they hold the same strokes in the same order. */
        fun <S : Any> diff(before: List<S>, after: List<S>): StrokeSplice<S>? {
            val inAfter = identitySet(after)
            val inBefore = identitySet(before)
            val removed = before.withIndex().filter { it.value !in inAfter }
            val added = after.withIndex().filter { it.value !in inBefore }
            if (removed.isEmpty() && added.isEmpty()) return null
            return StrokeSplice(removed, added)
        }

        private fun <S> identitySet(list: List<S>): MutableSet<S> =
            Collections.newSetFromMap(IdentityHashMap<S, Boolean>(list.size * 2)).apply { addAll(list) }

        private fun <S : Any> change(list: MutableList<S>, out: List<IndexedValue<S>>, into: List<IndexedValue<S>>) {
            val exact = out.all { (i, s) -> i < list.size && list[i] === s }
            if (exact) {
                for (k in out.indices.reversed()) list.removeAt(out[k].index)
            } else {
                // The list drifted (should not happen): fall back to identity removal.
                val gone = identitySet(out.map { it.value })
                list.removeAll { it in gone }
            }
            for ((i, s) in into) list.add(i.coerceAtMost(list.size), s)
        }
    }
}
