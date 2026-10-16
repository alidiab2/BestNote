package app.bestnote.core.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PenPreferencesTest {
    private val fountain = PenState("Fountain", 0xFF2A4BD7.toInt(), 7f, 1f, pressureSensitivity = 0.4f, stabilization = 60)
    private val prefs = PenPreferences(
        pens = listOf(fountain, PenState("Highlighter", 0xFFF7D84A.toInt(), 5f, opacity = 0.6f)),
        savedColors = listOf(0xFF123456.toInt(), 0xFFABCDEF.toInt()),
        favorites = listOf(FavoritePen("a", fountain), FavoritePen("b", fountain.copy(pen = "Brush", sizePx = 12f))),
    )

    @Test fun penSettingsSavedColorsAndFavoritesSurviveTheFileRoundTrip() = runTest {
        val out = ByteArrayOutputStream()
        UserPreferencesSerializer.writeTo(UserPreferences(pens = prefs), out)
        val back = UserPreferencesSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(prefs, back.pens)
    }

    @Test fun olderFilesWithoutPensReadAsDefaults() = runTest {
        val back = UserPreferencesSerializer.readFrom(ByteArrayInputStream("""{"themeMode":"Dark"}""".toByteArray()))
        assertEquals(PenPreferences(), back.pens)
        assertEquals(ThemeMode.Dark, back.themeMode)
    }

    @Test fun repositoryPersistsAndSanitizes() = runTest {
        val store = FakeDataStore(UserPreferences())
        val repo = UserPreferencesRepository(store)
        repo.updatePens { prefs }
        assertEquals(prefs, repo.pens.first())

        repo.updatePens {
            it.copy(
                pens = it.pens + PenState("Pencil", 0x00112233, -3f, opacity = 4f, pressureSensitivity = Float.NaN, stabilization = 400),
                savedColors = List(20) { i -> i } + 0xFF123456.toInt(),
                favorites = it.favorites + List(10) { i -> FavoritePen("x$i", fountain) },
            )
        }
        val saved = store.value.pens
        val pencil = saved.pens.single { it.pen == "Pencil" }
        assertEquals(0xFF112233.toInt(), pencil.colorArgb)
        assertEquals(WritingPreferences.DEFAULT_PEN_SIZE, pencil.sizePx)
        assertEquals(1f, pencil.opacity)
        assertEquals(1f, pencil.pressureSensitivity)
        assertEquals(100, pencil.stabilization)
        assertEquals(PenPreferences.MAX_SAVED_COLORS, saved.savedColors.size)
        assertEquals(saved.savedColors.distinct(), saved.savedColors)
        assertEquals(PenPreferences.MAX_FAVORITES, saved.favorites.size)
        assertEquals(listOf("a", "b"), saved.favorites.take(2).map { it.id })
    }
}
