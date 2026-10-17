package app.bestnote.core.ui.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutTableTest {
    private fun editor(code: Int, ctrl: Boolean = false, shift: Boolean = false) =
        ShortcutTable.resolve(ShortcutScope.Editor, code, ctrl, shift)

    @Test fun undoRedo() {
        assertEquals(ShortcutAction.Undo, editor(KeyEvent.KEYCODE_Z, ctrl = true))
        assertEquals(ShortcutAction.Redo, editor(KeyEvent.KEYCODE_Z, ctrl = true, shift = true))
        assertEquals(ShortcutAction.Redo, editor(KeyEvent.KEYCODE_Y, ctrl = true))
    }

    @Test fun toolsAreBareLetters() {
        assertEquals(ShortcutAction.ToolPen, editor(KeyEvent.KEYCODE_P))
        assertEquals(ShortcutAction.ToolHighlighter, editor(KeyEvent.KEYCODE_H))
        assertEquals(ShortcutAction.ToolEraser, editor(KeyEvent.KEYCODE_E))
        assertEquals(ShortcutAction.ToolLasso, editor(KeyEvent.KEYCODE_L))
        assertEquals(ShortcutAction.ToolText, editor(KeyEvent.KEYCODE_T))
        // Ctrl+P is not the pen.
        assertNull(editor(KeyEvent.KEYCODE_P, ctrl = true))
    }

    @Test fun zoomAndPages() {
        assertEquals(ShortcutAction.ZoomIn, editor(KeyEvent.KEYCODE_EQUALS, ctrl = true))
        assertEquals(ShortcutAction.ZoomIn, editor(KeyEvent.KEYCODE_EQUALS, ctrl = true, shift = true))
        assertEquals(ShortcutAction.ZoomOut, editor(KeyEvent.KEYCODE_MINUS, ctrl = true))
        assertEquals(ShortcutAction.ZoomReset, editor(KeyEvent.KEYCODE_0, ctrl = true))
        assertEquals(ShortcutAction.NextPage, editor(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(ShortcutAction.PreviousPage, editor(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(ShortcutAction.NewPage, editor(KeyEvent.KEYCODE_N, ctrl = true))
    }

    @Test fun selectionKeys() {
        assertEquals(ShortcutAction.Copy, editor(KeyEvent.KEYCODE_C, ctrl = true))
        assertEquals(ShortcutAction.Cut, editor(KeyEvent.KEYCODE_X, ctrl = true))
        assertEquals(ShortcutAction.Paste, editor(KeyEvent.KEYCODE_V, ctrl = true))
        assertEquals(ShortcutAction.Duplicate, editor(KeyEvent.KEYCODE_D, ctrl = true))
        assertEquals(ShortcutAction.Delete, editor(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(ShortcutAction.Delete, editor(KeyEvent.KEYCODE_DEL))
        assertEquals(ShortcutAction.ClearSelection, editor(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test fun libraryScopeSeparatesCtrlN() {
        assertEquals(ShortcutAction.NewNotebook, ShortcutTable.resolve(ShortcutScope.Library, KeyEvent.KEYCODE_N, ctrl = true, shift = false))
        assertEquals(ShortcutAction.SearchLibrary, ShortcutTable.resolve(ShortcutScope.Library, KeyEvent.KEYCODE_F, ctrl = true, shift = false))
        // Editor-only keys do nothing in the library, library keys nothing in the editor.
        assertNull(ShortcutTable.resolve(ShortcutScope.Library, KeyEvent.KEYCODE_P, ctrl = false, shift = false))
        assertNull(editor(KeyEvent.KEYCODE_F, ctrl = true))
    }

    @Test fun helpIsGlobal() {
        assertEquals(ShortcutAction.ShowShortcuts, ShortcutTable.resolve(ShortcutScope.Library, KeyEvent.KEYCODE_SLASH, ctrl = true, shift = false))
        assertEquals(ShortcutAction.ShowShortcuts, editor(KeyEvent.KEYCODE_SLASH, ctrl = true))
    }

    @Test fun nothingFiresWhileEditingText() {
        for ((_, combo) in ShortcutTable.bindings) {
            assertNull(ShortcutTable.resolve(ShortcutScope.Editor, combo.keyCode, combo.ctrl, combo.shift, editingText = true))
            assertNull(ShortcutTable.resolve(ShortcutScope.Library, combo.keyCode, combo.ctrl, combo.shift, editingText = true))
        }
    }

    @Test fun altOrMetaMeansNotOurs() {
        assertNull(ShortcutTable.resolve(ShortcutScope.Editor, KeyEvent.KEYCODE_P, ctrl = false, shift = false, alt = true))
        assertNull(ShortcutTable.resolve(ShortcutScope.Editor, KeyEvent.KEYCODE_Z, ctrl = true, shift = false, meta = true))
    }

    @Test fun noDuplicateBindingsInOneScope() {
        for (scope in listOf(ShortcutScope.Editor, ShortcutScope.Library)) {
            val seen = ShortcutTable.bindings.filter { it.first.scope == ShortcutScope.Global || it.first.scope == scope }.map { it.second }
            assertEquals("duplicate combo in $scope", seen.size, seen.toSet().size)
        }
    }

    @Test fun everyActionHasABindingAndLabel() {
        ShortcutAction.entries.forEach { assertTrue("$it unbound", ShortcutTable.combosOf(it).isNotEmpty()) }
        assertEquals("Ctrl+Shift+Z", KeyCombo(KeyEvent.KEYCODE_Z, ctrl = true, shift = true).label())
        assertEquals("PageDown", KeyCombo(KeyEvent.KEYCODE_PAGE_DOWN).label())
    }

    private fun text(code: Int, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false) =
        ShortcutTable.resolve(ShortcutScope.Text, code, ctrl, shift, alt = alt, editingText = true)

    @Test fun textFormattingWorksOnlyWhileTyping() {
        assertEquals(ShortcutAction.TextBold, text(KeyEvent.KEYCODE_B, ctrl = true))
        assertEquals(ShortcutAction.TextItalic, text(KeyEvent.KEYCODE_I, ctrl = true))
        assertEquals(ShortcutAction.TextUnderline, text(KeyEvent.KEYCODE_U, ctrl = true))
        assertEquals(ShortcutAction.TextNumbers, text(KeyEvent.KEYCODE_7, ctrl = true, shift = true))
        assertEquals(ShortcutAction.TextBullets, text(KeyEvent.KEYCODE_8, ctrl = true, shift = true))
        assertEquals(ShortcutAction.TextTitle, text(KeyEvent.KEYCODE_1, ctrl = true, alt = true))
        assertEquals(ShortcutAction.TextHeading, text(KeyEvent.KEYCODE_2, ctrl = true, alt = true))
        assertEquals(ShortcutAction.TextSubheading, text(KeyEvent.KEYCODE_3, ctrl = true, alt = true))
        assertEquals(ShortcutAction.TextIndent, text(KeyEvent.KEYCODE_TAB))
        assertEquals(ShortcutAction.TextOutdent, text(KeyEvent.KEYCODE_TAB, shift = true))
        // Not while the page has focus, and Ctrl+1 without Alt is nothing.
        assertNull(ShortcutTable.resolve(ShortcutScope.Text, KeyEvent.KEYCODE_B, ctrl = true, shift = false))
        assertNull(editor(KeyEvent.KEYCODE_B, ctrl = true))
        assertNull(text(KeyEvent.KEYCODE_1, ctrl = true))
        // Plain letters are typing, never tools.
        assertNull(text(KeyEvent.KEYCODE_P))
        assertEquals("Ctrl+Alt+1", KeyCombo(KeyEvent.KEYCODE_1, ctrl = true, alt = true).label())
    }

    @Test fun arrowsNudgeShiftTenTimes() {
        assertEquals(ShortcutAction.NudgeLeft, editor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(ShortcutAction.NudgeDownFar, editor(KeyEvent.KEYCODE_DPAD_DOWN, shift = true))
        assertTrue(ShortcutTable.combosOf(ShortcutAction.NudgeUpFar, includeAliases = false).isEmpty())
    }
}
