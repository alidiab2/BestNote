package app.bestnote.core.datastore

import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UserPreferencesSerializerTest {
    private suspend fun roundTrip(prefs: UserPreferences): UserPreferences {
        val out = ByteArrayOutputStream()
        UserPreferencesSerializer.writeTo(prefs, out)
        return UserPreferencesSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
    }

    private suspend fun read(json: String) = UserPreferencesSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))

    @Test fun roundTrip() = runTest {
        val prefs = UserPreferences(
            themeMode = ThemeMode.Dark,
            dynamicColor = true,
            stylus = StylusPreferences(SideButtonAction.Lasso, FingerDrawing.Never, hoverCursor = false, PressureCurve.Firm, tiltShading = false),
            gestures = GesturePreferences(twoFingerTapUndo = false, pinchToZoom = false),
            writing = WritingPreferences(PenStyle.Brush, 0xFF2A4BD7.toInt(), 8f, PaperTemplate.Grid, PaperSize.Letter),
            appearance = AppearancePreferences(ChromeWarmth.Neutral, reduceMotion = true),
            storage = StoragePreferences(trashRetentionDays = 7),
        )
        assertEquals(prefs, roundTrip(prefs))
    }

    @Test fun defaultsRoundTrip() = runTest {
        assertEquals(UserPreferences(), roundTrip(UserPreferences()))
    }

    @Test fun unknownKeysIgnored() = runTest {
        val prefs = read("""{"stylus":{"fingerDrawing":"Never"},"someFutureFlag":42}""")
        assertEquals(FingerDrawing.Never, prefs.stylus.fingerDrawing)
        assertTrue(prefs.stylusOnly)
        assertEquals(ThemeMode.System, prefs.themeMode)
    }

    @Test fun unknownEnumValueFallsBackToDefault() = runTest {
        val prefs = read("""{"stylus":{"sideButtonHold":"Teleport"},"themeMode":"Dark","hoverButtonAction":"Sideways"}""")
        assertEquals(SideButtonAction.TemporaryEraser, prefs.stylus.sideButtonHold)
        assertEquals(HoverButtonAction.QuickPalette, prefs.hoverButtonAction)
        assertEquals(ThemeMode.Dark, prefs.themeMode)
    }

    @Test(expected = androidx.datastore.core.CorruptionException::class)
    fun garbageIsCorruption() = runTest {
        read("not json")
    }

    @Test fun pressureCurvesAreMonotonicAndOrdered() {
        val p = 0.4f
        assertTrue(PressureCurve.Soft.apply(p) > PressureCurve.Normal.apply(p))
        assertTrue(PressureCurve.Firm.apply(p) < PressureCurve.Normal.apply(p))
        PressureCurve.entries.forEach {
            assertEquals(0f, it.apply(0f), 1e-6f)
            assertEquals(1f, it.apply(1f), 1e-6f)
            assertEquals(1f, it.apply(1.7f), 1e-6f)
        }
    }

    @Test fun editorChromeSettingsRoundTripAndDefault() = runTest {
        val prefs = UserPreferences(
            toolbarDock = ToolbarDock.Right,
            leftHanded = true,
            hoverButtonAction = HoverButtonAction.Off,
            hoverDoubleClickUndo = true,
        )
        assertEquals(prefs, roundTrip(prefs))
        // Files written before these settings existed keep the defaults.
        val old = read("""{"stylusOnly":false}""")
        assertEquals(ToolbarDock.Top, old.toolbarDock)
        assertEquals(HoverButtonAction.QuickPalette, old.hoverButtonAction)
        assertEquals(false, old.leftHanded)
    }
}
