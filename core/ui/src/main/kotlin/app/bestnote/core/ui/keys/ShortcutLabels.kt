package app.bestnote.core.ui.keys

import android.app.Activity
import android.content.Context
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.annotation.StringRes
import app.bestnote.core.ui.R

@StringRes
fun ShortcutAction.labelRes(): Int = when (this) {
    ShortcutAction.ShowShortcuts -> R.string.shortcut_show
    ShortcutAction.Undo -> R.string.shortcut_undo
    ShortcutAction.Redo -> R.string.shortcut_redo
    ShortcutAction.ToolPen -> R.string.shortcut_tool_pen
    ShortcutAction.ToolHighlighter -> R.string.shortcut_tool_highlighter
    ShortcutAction.ToolEraser -> R.string.shortcut_tool_eraser
    ShortcutAction.ToolLasso -> R.string.shortcut_tool_lasso
    ShortcutAction.ToolText -> R.string.shortcut_tool_text
    ShortcutAction.ZoomIn -> R.string.shortcut_zoom_in
    ShortcutAction.ZoomOut -> R.string.shortcut_zoom_out
    ShortcutAction.ZoomReset -> R.string.shortcut_zoom_reset
    ShortcutAction.NextPage -> R.string.shortcut_next_page
    ShortcutAction.PreviousPage -> R.string.shortcut_previous_page
    ShortcutAction.NewPage -> R.string.shortcut_new_page
    ShortcutAction.Copy -> R.string.shortcut_copy
    ShortcutAction.Cut -> R.string.shortcut_cut
    ShortcutAction.Paste -> R.string.shortcut_paste
    ShortcutAction.Duplicate -> R.string.shortcut_duplicate
    ShortcutAction.Delete -> R.string.shortcut_delete
    ShortcutAction.ClearSelection -> R.string.shortcut_clear_selection
    ShortcutAction.NudgeLeft -> R.string.shortcut_nudge_left
    ShortcutAction.NudgeRight -> R.string.shortcut_nudge_right
    ShortcutAction.NudgeUp -> R.string.shortcut_nudge_up
    ShortcutAction.NudgeDown -> R.string.shortcut_nudge_down
    ShortcutAction.NudgeLeftFar -> R.string.shortcut_nudge_left
    ShortcutAction.NudgeRightFar -> R.string.shortcut_nudge_right
    ShortcutAction.NudgeUpFar -> R.string.shortcut_nudge_up
    ShortcutAction.NudgeDownFar -> R.string.shortcut_nudge_down
    ShortcutAction.TextBold -> R.string.shortcut_text_bold
    ShortcutAction.TextItalic -> R.string.shortcut_text_italic
    ShortcutAction.TextUnderline -> R.string.shortcut_text_underline
    ShortcutAction.TextNumbers -> R.string.shortcut_text_numbers
    ShortcutAction.TextBullets -> R.string.shortcut_text_bullets
    ShortcutAction.TextTitle -> R.string.shortcut_text_title
    ShortcutAction.TextHeading -> R.string.shortcut_text_heading
    ShortcutAction.TextSubheading -> R.string.shortcut_text_subheading
    ShortcutAction.TextBody -> R.string.shortcut_text_body
    ShortcutAction.TextIndent -> R.string.shortcut_text_indent
    ShortcutAction.TextOutdent -> R.string.shortcut_text_outdent
    ShortcutAction.TextDone -> R.string.shortcut_text_done
    ShortcutAction.NewNotebook -> R.string.shortcut_new_notebook
    ShortcutAction.SearchLibrary -> R.string.shortcut_search_library
}

@StringRes
fun ShortcutGroup.labelRes(): Int = when (this) {
    ShortcutGroup.General -> R.string.shortcut_group_general
    ShortcutGroup.Edit -> R.string.shortcut_group_edit
    ShortcutGroup.Tools -> R.string.shortcut_group_tools
    ShortcutGroup.View -> R.string.shortcut_group_view
    ShortcutGroup.Pages -> R.string.shortcut_group_pages
    ShortcutGroup.Selection -> R.string.shortcut_group_selection
    ShortcutGroup.Text -> R.string.shortcut_group_text
    ShortcutGroup.Library -> R.string.shortcut_group_library
}

/** The shortcuts for the system list (Meta+/ via [Activity.onProvideKeyboardShortcuts]). */
fun keyboardShortcutGroups(context: Context): List<KeyboardShortcutGroup> =
    ShortcutGroup.entries.mapNotNull { group ->
        val infos = ShortcutTable.bindings
            .filter { (action, combo) -> action.group == group && !combo.alias }
            .map { (action, combo) ->
                val modifiers = (if (combo.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (combo.shift) KeyEvent.META_SHIFT_ON else 0) or
                    (if (combo.alt) KeyEvent.META_ALT_ON else 0)
                KeyboardShortcutInfo(context.getString(action.labelRes()), combo.keyCode, modifiers)
            }
        if (infos.isEmpty()) null else KeyboardShortcutGroup(context.getString(group.labelRes()), infos)
    }
