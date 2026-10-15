package app.bestnote.engine.canvas

/**
 * One reversible edit of a document [T] (add strokes, erase, and later transforms). Commands are
 * immutable values: [apply] and [revert] must be exact inverses so any undo/redo order is safe.
 */
interface Command<in T> {
    fun apply(target: T)
    fun revert(target: T)

    /** Rough memory this command keeps alive (e.g. erased strokes), counted against [CommandStack.maxBytes]. */
    val retainedBytes: Long
}

/**
 * Bounded undo/redo history for one document (CANVAS-03). Pure Kotlin.
 *
 * - Lives as long as the document is open, independent of the selected tool, so switching pens
 *   never loses history.
 * - Bounded twice: at most [maxOperations] undo steps, and at most [maxBytes] of retained data
 *   across undo + redo. The oldest steps are dropped first; the newest step is always kept, so the
 *   last edit can be undone even when it alone is larger than the budget.
 * - [record] (or [execute]) starts a new branch: the redo history is discarded.
 */
class CommandStack<T>(
    val maxOperations: Int = DEFAULT_MAX_OPERATIONS,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxOperations >= 1) { "maxOperations must be >= 1" }
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
    }

    private val undo = ArrayDeque<Command<T>>()
    private val redo = ArrayDeque<Command<T>>()

    /** Sum of [Command.retainedBytes] over every command still in the history. */
    var retainedBytes: Long = 0
        private set

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoDepth: Int get() = undo.size
    val redoDepth: Int get() = redo.size

    /** Applies [command] to [target] and records it. */
    fun execute(command: Command<T>, target: T) {
        command.apply(target)
        record(command)
    }

    /** Records a [command] the caller already applied (e.g. a stroke the ink layer just finished). */
    fun record(command: Command<T>) {
        while (redo.isNotEmpty()) retainedBytes -= redo.removeLast().retainedBytes
        undo.addLast(command)
        retainedBytes += command.retainedBytes
        trim()
    }

    /** Reverts the newest step on [target]. @return the reverted command, or null when there is none. */
    fun undo(target: T): Command<T>? {
        val command = undo.removeLastOrNull() ?: return null
        command.revert(target)
        redo.addLast(command)
        return command
    }

    /** Re-applies the most recently undone step on [target]. @return the command, or null when there is none. */
    fun redo(target: T): Command<T>? {
        val command = redo.removeLastOrNull() ?: return null
        command.apply(target)
        undo.addLast(command)
        return command
    }

    fun clear() {
        undo.clear()
        redo.clear()
        retainedBytes = 0
    }

    private fun trim() {
        while (undo.size > maxOperations || (retainedBytes > maxBytes && undo.size > 1)) {
            retainedBytes -= undo.removeFirst().retainedBytes
        }
    }

    companion object {
        /** Comfortably above the 200 steps CANVAS-03 requires. */
        const val DEFAULT_MAX_OPERATIONS = 500

        /** History may keep at most this much erased/replaced ink alive. */
        const val DEFAULT_MAX_BYTES: Long = 32L * 1024 * 1024
    }
}
