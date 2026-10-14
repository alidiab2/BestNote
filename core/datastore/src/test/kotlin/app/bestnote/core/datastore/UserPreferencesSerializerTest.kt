package app.bestnote.core.datastore

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UserPreferencesSerializerTest {
    @Test fun roundTrip() = runTest {
        val prefs = UserPreferences(stylusOnly = true, themeMode = ThemeMode.Dark, dynamicColor = false)
        val out = ByteArrayOutputStream()
        UserPreferencesSerializer.writeTo(prefs, out)
        assertEquals(prefs, UserPreferencesSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test fun unknownKeysIgnored() = runTest {
        val json = """{"stylusOnly":true,"someFutureFlag":42}"""
        val prefs = UserPreferencesSerializer.readFrom(ByteArrayInputStream(json.encodeToByteArray()))
        assertEquals(true, prefs.stylusOnly)
        assertEquals(ThemeMode.System, prefs.themeMode)
    }
}
