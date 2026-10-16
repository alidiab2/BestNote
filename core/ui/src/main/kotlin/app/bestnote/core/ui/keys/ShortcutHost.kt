package app.bestnote.core.ui.keys

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Routes hardware-keyboard shortcuts (SPEN-10) from the activity to the screen that is up.
 * Screens call [ShortcutHandler]; the activity forwards every key event to [onKeyEvent] first, so
 * shortcuts work whichever view holds focus. Also owns the help overlay's visibility.
 */
class ShortcutHost {
    private class Entry(val scope: ShortcutScope, var handler: (ShortcutAction) -> Boolean)

    private val entries = ArrayList<Entry>()
    private val typists = ArrayList<(KeyEvent) -> Boolean>()
    private val textOwners = HashSet<Any>()

    /** Meta is held down: the help overlay shows for as long as it is. */
    var metaHeld by mutableStateOf(false)
        private set

    /** Ctrl+/ pinned the help open. */
    var pinned by mutableStateOf(false)

    val helpVisible: Boolean get() = metaHeld || pinned

    /** A text field reports it has (or lost) focus, so typing is never taken for a shortcut. */
    fun setTextEditing(owner: Any, editing: Boolean) {
        if (editing) textOwners.add(owner) else textOwners.remove(owner)
    }

    val editingText: Boolean get() = textOwners.isNotEmpty()

    /** Adds a screen's handler; the most recently added one sees keys first. Returns the remover. */
    fun register(scope: ShortcutScope, handler: (ShortcutAction) -> Boolean): () -> Unit {
        val entry = Entry(scope, handler)
        entries.add(entry)
        return { entries.remove(entry) }
    }

    /**
     * Adds a handler for plain typing that no shortcut took while no text field has focus (TEXT-04: typing with the
     * Text tool starts a text box). The most recently added one is asked. Returns the remover.
     */
    fun registerTyping(handler: (KeyEvent) -> Boolean): () -> Unit {
        typists.add(handler)
        return { typists.remove(handler) }
    }

    /** Forgets held keys (the window lost focus). */
    fun reset() {
        metaHeld = false
    }

    /**
     * @param focusIsTextEditor the view that has focus is a text editor (an Android EditText or a focused Compose text field).
     * @return true when the event was a shortcut and was handled.
     */
    fun onKeyEvent(event: KeyEvent, focusIsTextEditor: Boolean = false): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) metaHeld = true else if (event.action == KeyEvent.ACTION_UP) metaHeld = false
            return false // the system still sees Meta
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val editing = editingText || focusIsTextEditor
        if (!editing && pinned && event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            pinned = false
            return true
        }
        val repeat = event.repeatCount > 0
        for (entry in entries.asReversed()) {
            val action = ShortcutTable.resolve(
                entry.scope, event.keyCode, event.isCtrlPressed, event.isShiftPressed,
                alt = event.isAltPressed, meta = event.isMetaPressed, editingText = editing,
            ) ?: continue
            if (repeat && !action.repeatable) return true // a held key does not fire twice
            if (action == ShortcutAction.ShowShortcuts) {
                pinned = !pinned
                return true
            }
            if (entry.handler(action)) return true
        }
        val typing = !editing && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && event.unicodeChar != 0
        return typing && typists.lastOrNull()?.invoke(event) == true
    }
}

val LocalShortcutHost = compositionLocalOf { ShortcutHost() }

/** Registers [onAction] for [scope] while this is in the composition. Return true from it when the action was used. */
@Composable
fun ShortcutHandler(scope: ShortcutScope, onAction: (ShortcutAction) -> Boolean) {
    val host = LocalShortcutHost.current
    val current by rememberUpdatedState(onAction)
    DisposableEffect(host, scope) {
        val remove = host.register(scope) { current(it) }
        onDispose(remove)
    }
}

/** Registers [onType] for plain typing no shortcut took (see [ShortcutHost.registerTyping]) while in the composition. */
@Composable
fun TypingHandler(onType: (KeyEvent) -> Boolean) {
    val host = LocalShortcutHost.current
    val current by rememberUpdatedState(onType)
    DisposableEffect(host) {
        val remove = host.registerTyping { current(it) }
        onDispose(remove)
    }
}

/** Marks a text field: while it has focus no shortcut fires. */
@Composable
fun Modifier.reportsTextEditing(): Modifier {
    val host = LocalShortcutHost.current
    val owner = androidx.compose.runtime.remember { Any() }
    DisposableEffect(host, owner) { onDispose { host.setTextEditing(owner, false) } }
    return onFocusChanged { host.setTextEditing(owner, it.isFocused) }
}
