package app.bestnote.engine.canvas.select

import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Pure rules for grouping and stacking order of a page's objects (CANVAS-14). Objects (strokes, elements)
 * are compared by identity; their [groupOf] is an id shared by everything grouped together.
 */
object ObjectOrder {
    fun newGroupId(): String = "g-" + UUID.randomUUID().toString().take(ID_LENGTH)

    /**
     * [chosen] plus every other object of [all] that shares a group with one of them, in [all]'s order;
     * objects for which [selectable] is false (locked) are never added.
     */
    fun <T : Any> withGroupMates(chosen: List<T>, all: List<T>, groupOf: (T) -> String?, selectable: (T) -> Boolean = { true }): List<T> {
        val groups = chosen.mapNotNullTo(HashSet(), groupOf)
        if (groups.isEmpty()) return chosen
        val picked = identitySet(chosen)
        val extra = all.filter { it !in picked && selectable(it) && groupOf(it) in groups }
        if (extra.isEmpty()) return chosen
        val result = identitySet(chosen).also { it.addAll(extra) }
        return all.filter { it in result }
    }

    /** [all] with [selected] moved, keeping their relative order, to the end (front: drawn last, on top) or the start (back). */
    fun <T : Any> reordered(all: List<T>, selected: Collection<T>, toFront: Boolean): List<T> {
        val sel = identitySet(selected)
        val moved = all.filter { it in sel }
        val rest = all.filter { it !in sel }
        return if (toFront) rest + moved else moved + rest
    }

    /** Does [reordered] change anything? False when the selection already sits at that end. */
    fun <T : Any> wouldMove(all: List<T>, selected: Collection<T>, toFront: Boolean): Boolean {
        val next = reordered(all, selected, toFront)
        return next.indices.any { next[it] !== all[it] }
    }

    /**
     * Fresh group ids for pasted or duplicated objects: [old] ids map one-to-one to new ids, so a copied
     * group stays a group of its own and never merges with the original.
     */
    class Regrouper {
        private val map = HashMap<String, String>()
        fun of(old: String?): String? = old?.let { map.getOrPut(it) { newGroupId() } }
    }

    private fun <T> identitySet(items: Collection<T>): MutableSet<T> =
        Collections.newSetFromMap(IdentityHashMap<T, Boolean>(items.size * 2 + 1)).apply { addAll(items) }

    private const val ID_LENGTH = 12
}
