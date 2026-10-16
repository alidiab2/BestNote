package app.bestnote.ai.vision.providers

import app.bestnote.engine.layout.StyleTemplates
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartBoardStylesTest {
    private class Memory : StyleStorage {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test fun `each notebook keeps its own style and new notebooks start from the default`() {
        val storage = Memory()
        val styles = SmartBoardStyles(storage)
        assertEquals(StyleTemplates.Classic, styles.styleFor("physics"))
        styles.defaultStyle = StyleTemplates.Minimal
        assertEquals(StyleTemplates.Minimal, styles.styleFor("physics"))
        styles.setStyleFor("physics", StyleTemplates.Colorful)
        styles.setStyleFor("maths", StyleTemplates.CheatSheet)
        // Survives a restart (a new instance on the same storage).
        val again = SmartBoardStyles(storage)
        assertEquals(StyleTemplates.Colorful, again.styleFor("physics"))
        assertEquals(StyleTemplates.CheatSheet, again.styleFor("maths"))
        assertEquals(StyleTemplates.Minimal, again.styleFor("history"))
        assertEquals(WritingMode.Font, again.writingMode)
        again.writingMode = WritingMode.Strokes
        assertEquals(WritingMode.Strokes, SmartBoardStyles(storage).writingMode)
    }

    @Test fun `an unknown stored style falls back to classic`() {
        val storage = Memory().apply { map["notebook:x"] = "gone" }
        assertEquals(StyleTemplates.Classic, SmartBoardStyles(storage).styleFor("x"))
    }
}
