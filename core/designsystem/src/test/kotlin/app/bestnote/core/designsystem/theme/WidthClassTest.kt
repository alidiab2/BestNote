package app.bestnote.core.designsystem.theme

import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.Assert.assertEquals

class WidthClassTest {
    @Test fun `phone and fold outer screen are compact`() {
        assertEquals(WidthClass.Compact, WidthClass.of(360.dp))
        assertEquals(WidthClass.Compact, WidthClass.of(599.dp))
    }

    @Test fun `fold inner screen and small tablets are medium`() {
        assertEquals(WidthClass.Medium, WidthClass.of(600.dp))
        assertEquals(WidthClass.Medium, WidthClass.of(673.dp))
        assertEquals(WidthClass.Medium, WidthClass.of(839.dp))
    }

    @Test fun `tablets and desktop windows are expanded`() {
        assertEquals(WidthClass.Expanded, WidthClass.of(840.dp))
        assertEquals(WidthClass.Expanded, WidthClass.of(1280.dp))
    }
}
