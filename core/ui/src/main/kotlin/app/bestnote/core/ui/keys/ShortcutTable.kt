package app.bestnote.core.ui.keys

import android.view.KeyEvent

/** Where a shortcut applies: the screen that is up. [Global] works everywhere. */
enum class ShortcutScope { Global, Editor, Library }

/** What a hardware-keyboard shortcut does (SPEN-10). Screens map these to their own behavior. */
enum class ShortcutAction(val scope: ShortcutScope, val group: ShortcutGroup, val repeatable: Boolean = false) {
    ShowShortcuts(ShortcutScope.Global, ShortcutGroup.General),

    Undo(ShortcutScope.Editor, ShortcutGroup.Edit, repeatable = true),
    Redo(ShortcutScope.Editor, ShortcutGroup.Edit, repeatable = true),
    ToolPen(ShortcutScope.Editor, ShortcutGroup.Tools),
    ToolHighlighter(ShortcutScope.Editor, ShortcutGroup.Tools),
    ToolEraser(ShortcutScope.Editor, ShortcutGroup.Tools),
    ToolLasso(ShortcutScope.Editor, ShortcutGroup.Tools),
    ToolText(ShortcutScope.Editor, ShortcutGroup.Tools),
    ZoomIn(ShortcutScope.Editor, ShortcutGroup.View, repeatable = true),
    ZoomOut(ShortcutScope.Editor, ShortcutGroup.View, repeatable = true),
    ZoomReset(ShortcutScope.Editor, ShortcutGroup.View),
    NextPage(ShortcutScope.Editor, ShortcutGroup.Pages, repeatable = true),
    PreviousPage(ShortcutScope.Editor, ShortcutGroup.Pages, repeatable = true),
    NewPage(ShortcutScope.Editor, ShortcutGroup.Pages),
    Copy(ShortcutScope.Editor, ShortcutGroup.Selection),
    Cut(ShortcutScope.Editor, ShortcutGroup.Selection),
    Paste(ShortcutScope.Editor, ShortcutGroup.Selection),
    Duplicate(ShortcutScope.Editor, ShortcutGroup.Selection),
    Delete(ShortcutScope.Editor, ShortcutGroup.Selection),
    ClearSelection(ShortcutScope.Editor, ShortcutGroup.Selection),

    NewNotebook(ShortcutScope.Library, ShortcutGroup.Library),
    SearchLibrary(ShortcutScope.Library, ShortcutGroup.Library),
}

/** Sections of the help overlay, in display order. */
enum class ShortcutGroup { General, Edit, Tools, View, Pages, Selection, Library }

/** One key plus modifiers. [ctrl] means Ctrl (Meta is never part of a binding: holding it shows the help). */
data class KeyCombo(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alias: Boolean = false) {
    /** [alias] combos work but the help overlay does not list them. Human label, like "Ctrl+Shift+Z" ([keyName] names the key; defaults to the Android key name). */
    fun label(keyName: (Int) -> String = ::defaultKeyName): String =
        listOfNotNull("Ctrl".takeIf { ctrl }, "Shift".takeIf { shift }, keyName(keyCode)).joinToString("+")
}

private fun defaultKeyName(code: Int): String = when (code) {
    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    KeyEvent.KEYCODE_EQUALS -> "="
    KeyEvent.KEYCODE_MINUS -> "-"
    KeyEvent.KEYCODE_SLASH -> "/"
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (code - KeyEvent.KEYCODE_0).toString()
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (code - KeyEvent.KEYCODE_A)).toString()
    else -> "?"
}

/**
 * The key to action table (SPEN-10). Pure: no Android classes beyond key code constants, so it is unit tested.
 * Letter tools fire on the bare key; everything else needs Ctrl (or is a navigation key).
 */
object ShortcutTable {
    /** Every binding, first entry per action is the one the help overlay shows first. */
    val bindings: List<Pair<ShortcutAction, KeyCombo>> = listOf(
        ShortcutAction.ShowShortcuts to KeyCombo(KeyEvent.KEYCODE_SLASH, ctrl = true),
        ShortcutAction.Undo to KeyCombo(KeyEvent.KEYCODE_Z, ctrl = true),
        ShortcutAction.Redo to KeyCombo(KeyEvent.KEYCODE_Z, ctrl = true, shift = true),
        ShortcutAction.Redo to KeyCombo(KeyEvent.KEYCODE_Y, ctrl = true),
        ShortcutAction.ToolPen to KeyCombo(KeyEvent.KEYCODE_P),
        ShortcutAction.ToolHighlighter to KeyCombo(KeyEvent.KEYCODE_H),
        ShortcutAction.ToolEraser to KeyCombo(KeyEvent.KEYCODE_E),
        ShortcutAction.ToolLasso to KeyCombo(KeyEvent.KEYCODE_L),
        ShortcutAction.ToolText to KeyCombo(KeyEvent.KEYCODE_T),
        ShortcutAction.ZoomIn to KeyCombo(KeyEvent.KEYCODE_EQUALS, ctrl = true),
        ShortcutAction.ZoomIn to KeyCombo(KeyEvent.KEYCODE_EQUALS, ctrl = true, shift = true, alias = true), // Ctrl++
        ShortcutAction.ZoomIn to KeyCombo(KeyEvent.KEYCODE_NUMPAD_ADD, ctrl = true, alias = true),
        ShortcutAction.ZoomOut to KeyCombo(KeyEvent.KEYCODE_MINUS, ctrl = true),
        ShortcutAction.ZoomOut to KeyCombo(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, ctrl = true, alias = true),
        ShortcutAction.ZoomReset to KeyCombo(KeyEvent.KEYCODE_0, ctrl = true),
        ShortcutAction.ZoomReset to KeyCombo(KeyEvent.KEYCODE_NUMPAD_0, ctrl = true, alias = true),
        ShortcutAction.NextPage to KeyCombo(KeyEvent.KEYCODE_PAGE_DOWN),
        ShortcutAction.PreviousPage to KeyCombo(KeyEvent.KEYCODE_PAGE_UP),
        ShortcutAction.NewPage to KeyCombo(KeyEvent.KEYCODE_N, ctrl = true),
        ShortcutAction.Copy to KeyCombo(KeyEvent.KEYCODE_C, ctrl = true),
        ShortcutAction.Cut to KeyCombo(KeyEvent.KEYCODE_X, ctrl = true),
        ShortcutAction.Paste to KeyCombo(KeyEvent.KEYCODE_V, ctrl = true),
        ShortcutAction.Duplicate to KeyCombo(KeyEvent.KEYCODE_D, ctrl = true),
        ShortcutAction.Delete to KeyCombo(KeyEvent.KEYCODE_FORWARD_DEL),
        ShortcutAction.Delete to KeyCombo(KeyEvent.KEYCODE_DEL),
        ShortcutAction.ClearSelection to KeyCombo(KeyEvent.KEYCODE_ESCAPE),
        ShortcutAction.NewNotebook to KeyCombo(KeyEvent.KEYCODE_N, ctrl = true),
        ShortcutAction.SearchLibrary to KeyCombo(KeyEvent.KEYCODE_F, ctrl = true),
    )

    /**
     * The action for a key press while [scope]'s screen is up, or null. Alt or Meta held means "not ours".
     * [editingText] (a text field has focus) disables everything, so typing is never hijacked.
     */
    fun resolve(
        scope: ShortcutScope,
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean = false,
        meta: Boolean = false,
        editingText: Boolean = false,
    ): ShortcutAction? {
        if (editingText || alt || meta) return null
        return bindings.firstOrNull { (action, combo) ->
            (action.scope == ShortcutScope.Global || action.scope == scope) &&
                combo.keyCode == keyCode && combo.ctrl == ctrl && combo.shift == shift
        }?.first
    }

    /** The combos bound to [action], display order. */
    fun combosOf(action: ShortcutAction, includeAliases: Boolean = true): List<KeyCombo> =
        bindings.filter { it.first == action && (includeAliases || !it.second.alias) }.map { it.second }
}
