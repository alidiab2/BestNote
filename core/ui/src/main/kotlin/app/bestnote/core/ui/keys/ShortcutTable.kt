package app.bestnote.core.ui.keys

import android.view.KeyEvent

/**
 * Where a shortcut applies: the screen that is up. [Global] works everywhere. [Text] works only while a text box is
 * being typed into on the page (TEXT-04), and is the only scope that does.
 */
enum class ShortcutScope { Global, Editor, Library, Text }

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
    NudgeLeft(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),
    NudgeRight(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),
    NudgeUp(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),
    NudgeDown(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),
    NudgeLeftFar(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),
    NudgeRightFar(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),
    NudgeUpFar(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),
    NudgeDownFar(ShortcutScope.Editor, ShortcutGroup.Selection, repeatable = true),

    TextBold(ShortcutScope.Text, ShortcutGroup.Text),
    TextItalic(ShortcutScope.Text, ShortcutGroup.Text),
    TextUnderline(ShortcutScope.Text, ShortcutGroup.Text),
    TextNumbers(ShortcutScope.Text, ShortcutGroup.Text),
    TextBullets(ShortcutScope.Text, ShortcutGroup.Text),
    TextTitle(ShortcutScope.Text, ShortcutGroup.Text),
    TextHeading(ShortcutScope.Text, ShortcutGroup.Text),
    TextSubheading(ShortcutScope.Text, ShortcutGroup.Text),
    TextBody(ShortcutScope.Text, ShortcutGroup.Text),
    TextIndent(ShortcutScope.Text, ShortcutGroup.Text, repeatable = true),
    TextOutdent(ShortcutScope.Text, ShortcutGroup.Text, repeatable = true),
    TextDone(ShortcutScope.Text, ShortcutGroup.Text),

    NewNotebook(ShortcutScope.Library, ShortcutGroup.Library),
    SearchLibrary(ShortcutScope.Library, ShortcutGroup.Library),
}

/** Sections of the help overlay, in display order. */
enum class ShortcutGroup { General, Edit, Tools, View, Pages, Selection, Text, Library }

/** One key plus modifiers. [ctrl] means Ctrl (Meta is never part of a binding: holding it shows the help). */
data class KeyCombo(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alias: Boolean = false, val alt: Boolean = false) {
    /** [alias] combos work but the help overlay does not list them. Human label, like "Ctrl+Shift+Z" ([keyName] names the key; defaults to the Android key name). */
    fun label(keyName: (Int) -> String = ::defaultKeyName): String =
        listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, keyName(keyCode)).joinToString("+")
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
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_DPAD_LEFT -> "←"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
    KeyEvent.KEYCODE_DPAD_UP -> "↑"
    KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
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
        ShortcutAction.NudgeLeft to KeyCombo(KeyEvent.KEYCODE_DPAD_LEFT),
        ShortcutAction.NudgeRight to KeyCombo(KeyEvent.KEYCODE_DPAD_RIGHT),
        ShortcutAction.NudgeUp to KeyCombo(KeyEvent.KEYCODE_DPAD_UP),
        ShortcutAction.NudgeDown to KeyCombo(KeyEvent.KEYCODE_DPAD_DOWN),
        // Shift = ten times as far; the help shows them in one row ("Nudge, Shift = 10x").
        ShortcutAction.NudgeLeftFar to KeyCombo(KeyEvent.KEYCODE_DPAD_LEFT, shift = true, alias = true),
        ShortcutAction.NudgeRightFar to KeyCombo(KeyEvent.KEYCODE_DPAD_RIGHT, shift = true, alias = true),
        ShortcutAction.NudgeUpFar to KeyCombo(KeyEvent.KEYCODE_DPAD_UP, shift = true, alias = true),
        ShortcutAction.NudgeDownFar to KeyCombo(KeyEvent.KEYCODE_DPAD_DOWN, shift = true, alias = true),
        ShortcutAction.TextBold to KeyCombo(KeyEvent.KEYCODE_B, ctrl = true),
        ShortcutAction.TextItalic to KeyCombo(KeyEvent.KEYCODE_I, ctrl = true),
        ShortcutAction.TextUnderline to KeyCombo(KeyEvent.KEYCODE_U, ctrl = true),
        ShortcutAction.TextNumbers to KeyCombo(KeyEvent.KEYCODE_7, ctrl = true, shift = true),
        ShortcutAction.TextBullets to KeyCombo(KeyEvent.KEYCODE_8, ctrl = true, shift = true),
        ShortcutAction.TextTitle to KeyCombo(KeyEvent.KEYCODE_1, ctrl = true, alt = true),
        ShortcutAction.TextHeading to KeyCombo(KeyEvent.KEYCODE_2, ctrl = true, alt = true),
        ShortcutAction.TextSubheading to KeyCombo(KeyEvent.KEYCODE_3, ctrl = true, alt = true),
        ShortcutAction.TextBody to KeyCombo(KeyEvent.KEYCODE_0, ctrl = true, alt = true),
        ShortcutAction.TextIndent to KeyCombo(KeyEvent.KEYCODE_TAB),
        ShortcutAction.TextOutdent to KeyCombo(KeyEvent.KEYCODE_TAB, shift = true),
        ShortcutAction.TextDone to KeyCombo(KeyEvent.KEYCODE_ESCAPE),
        ShortcutAction.NewNotebook to KeyCombo(KeyEvent.KEYCODE_N, ctrl = true),
        ShortcutAction.SearchLibrary to KeyCombo(KeyEvent.KEYCODE_F, ctrl = true),
    )

    /**
     * The action for a key press while [scope]'s screen is up, or null. Meta held means "not ours", and so does Alt
     * unless the binding asks for it. [editingText] (a text field has focus) disables everything but the [ShortcutScope.Text]
     * formatting keys, so typing is never hijacked; those fire only then.
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
        if (meta) return null
        return bindings.firstOrNull { (action, combo) ->
            val inScope = if (editingText) {
                action.scope == ShortcutScope.Text && scope == ShortcutScope.Text
            } else {
                action.scope == ShortcutScope.Global || (action.scope == scope && scope != ShortcutScope.Text)
            }
            inScope && combo.keyCode == keyCode && combo.ctrl == ctrl && combo.shift == shift && combo.alt == alt
        }?.first
    }

    /** The combos bound to [action], display order. */
    fun combosOf(action: ShortcutAction, includeAliases: Boolean = true): List<KeyCombo> =
        bindings.filter { it.first == action && (includeAliases || !it.second.alias) }.map { it.second }
}
