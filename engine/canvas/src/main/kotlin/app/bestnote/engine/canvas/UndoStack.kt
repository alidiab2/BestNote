package app.bestnote.engine.canvas

/**
 * Bounded undo/redo history of reversible edits [E]. Pure Kotlin; callers apply/revert
 * the returned edits themselves.
 */
class UndoStack<E>(private val capacity: Int = 200) {
    private val undo = ArrayDeque<E>()
    private val redo = ArrayDeque<E>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Records a new edit; clears the redo branch. */
    fun push(edit: E) {
        undo.addLast(edit)
        if (undo.size > capacity) undo.removeFirst()
        redo.clear()
    }

    /** @return the edit to revert, or null when there is nothing to undo. */
    fun undo(): E? = undo.removeLastOrNull()?.also { redo.addLast(it) }

    /** @return the edit to re-apply, or null when there is nothing to redo. */
    fun redo(): E? = redo.removeLastOrNull()?.also { undo.addLast(it) }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
