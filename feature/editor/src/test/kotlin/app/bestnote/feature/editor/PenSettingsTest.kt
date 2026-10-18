package app.bestnote.feature.editor

import app.bestnote.core.datastore.FavoritePen
import app.bestnote.core.datastore.PenPreferences
import app.bestnote.core.datastore.PenState
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesSerializer
import app.bestnote.core.model.LineDash
import app.bestnote.engine.ink.InkPalette
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PenSettingsTest {
    private val custom = 0xFF123456.toInt()

    @Test fun sensitivityAndStabilizationArePerPenAndClamped() {
        val box = Toolbox()
            .withSensitivity(0.3f)
            .withStabilization(70)
            .select(PenKind.Brush)
            .withSensitivity(5f)
            .withStabilization(-4)
        assertEquals(0.3f, box.settingsOf(PenKind.Fountain).pressureSensitivity)
        assertEquals(70, box.settingsOf(PenKind.Fountain).stabilization)
        assertEquals(1f, box.current.pressureSensitivity)
        assertEquals(0, box.current.stabilization)
        val tuning = box.settingsOf(PenKind.Fountain).tuning(curveGamma = 1.8f)
        assertEquals(0.3f, tuning.pressureSensitivity)
        assertEquals(70, tuning.stabilization)
        assertEquals(1.8f, tuning.curveGamma)
    }

    @Test fun defaultsLeaveInputUntouched() {
        PenKind.entries.forEach { assertTrue(Toolbox().settingsOf(it).tuning().isIdentity) }
    }

    @Test fun savedColorsAreNewestFirstWithoutDuplicatesAndCapped() {
        var box = Toolbox().saveColor(custom).saveColor(InkPalette.RED).saveColor(custom and 0x00FFFFFF)
        assertEquals(listOf(custom, InkPalette.RED), box.savedColors)
        repeat(20) { box = box.saveColor(0xFF000000.toInt() or it) }
        assertEquals(Toolbox.MAX_SAVED_COLORS, box.savedColors.size)
        assertEquals(box, box.removeSavedColor(0x0BADBAD))
        assertFalse(box.removeSavedColor(box.savedColors.first()).savedColors.contains(box.savedColors.first()))
    }

    @Test fun aFavoriteIsOneTapBackToTheWholePen() {
        val fav = Toolbox().select(PenKind.Brush).withColor(custom).withSize(9f).withSensitivity(0.4f).withStabilization(80).addFavorite("f1")
        assertEquals("f1", fav.activeFavorite?.id)
        assertFalse(fav.canAddFavorite)
        assertEquals(fav, fav.addFavorite("dup"))

        // Wander off: another pen, other settings, the lasso.
        val away = fav.withColor(InkPalette.BLUE).select(PenKind.Highlighter).withLasso(true)
        assertNull(away.activeFavorite)
        val back = away.applyFavorite(away.favorites.single())
        assertEquals(PenKind.Brush, back.active)
        assertFalse(back.lasso)
        assertEquals(PenSettings(custom, 9f, 1f, 0.4f, 80), back.current)
        assertEquals(custom, back.quickColors().first())
        assertEquals("f1", back.activeFavorite?.id)
    }

    @Test fun favoritesAreCappedAndRemovable() {
        var box = Toolbox()
        repeat(Toolbox.MAX_FAVORITES + 2) { box = box.withSize(2f + it).addFavorite("f$it") }
        assertEquals(Toolbox.MAX_FAVORITES, box.favorites.size)
        assertFalse(box.canAddFavorite)
        box = box.removeFavorite("f0")
        assertEquals(Toolbox.MAX_FAVORITES - 1, box.favorites.size)
        assertFalse(Toolbox().select(PenKind.Eraser).canAddFavorite)
    }

    @Test fun pensColorsAndFavoritesRoundTripThroughTheSettingsFile() = runBlocking {
        val box = Toolbox()
            .withColor(custom).withSize(11f).withSensitivity(0.25f).withStabilization(45)
            .select(PenKind.Highlighter).withOpacity(0.6f).withStabilization(100)
            .saveColor(custom).saveColor(InkPalette.GREEN)
            .addFavorite("hl")
            .select(PenKind.Fountain).addFavorite("fp")
        val out = ByteArrayOutputStream()
        UserPreferencesSerializer.writeTo(UserPreferences(pens = PenStore.toPrefs(box)), out)
        val read = UserPreferencesSerializer.readFrom(ByteArrayInputStream(out.toByteArray())).pens

        val restored = PenStore.restore(Toolbox(), read)
        PenKind.entries.filter { it.inks }.forEach { assertEquals(it.name, box.settingsOf(it), restored.settingsOf(it)) }
        assertEquals(box.savedColors, restored.savedColors)
        assertEquals(box.favorites, restored.favorites)
        assertEquals(custom, restored.quickColors(PenKind.Fountain).first())
        // Session state isn't persisted.
        assertEquals(PenKind.Fountain, restored.active)
    }

    @Test fun restoreSkipsUnknownPensAndClampsSizes() {
        val prefs = PenPreferences(
            pens = listOf(PenState("Crayon", custom, 5f), PenState("Brush", custom, 500f), PenState("Eraser", custom, 5f)),
            favorites = listOf(FavoritePen("x", PenState("Crayon", custom, 5f))),
        )
        val box = PenStore.restore(Toolbox(), prefs)
        assertEquals(PenDefaults.sizeRange(PenKind.Brush).endInclusive, box.settingsOf(PenKind.Brush).size)
        assertEquals(PenDefaults.settings.getValue(PenKind.Eraser), box.settingsOf(PenKind.Eraser))
        assertTrue(box.favorites.isEmpty())
    }

    @Test fun lineDashIsPerPenPersistedAndNeverOnTheHighlighter() = runBlocking {
        val box = Toolbox()
            .withDash(LineDash.Dashed)
            .select(PenKind.Pencil).withDash(LineDash.Dotted)
            .select(PenKind.Highlighter).withDash(LineDash.Dashed)
        assertEquals(LineDash.Dashed, box.settingsOf(PenKind.Fountain).dash)
        assertEquals(LineDash.Dotted, box.settingsOf(PenKind.Pencil).dash)
        assertEquals(LineDash.Solid, box.settingsOf(PenKind.Ballpoint).dash)
        assertEquals(LineDash.Solid, box.settingsOf(PenKind.Highlighter).dash)
        val out = ByteArrayOutputStream()
        UserPreferencesSerializer.writeTo(UserPreferences(pens = PenStore.toPrefs(box.addFavorite("hl").select(PenKind.Pencil).addFavorite("pc"))), out)
        val read = UserPreferencesSerializer.readFrom(ByteArrayInputStream(out.toByteArray())).pens
        val restored = PenStore.restore(Toolbox(), read)
        assertEquals(LineDash.Dashed, restored.settingsOf(PenKind.Fountain).dash)
        assertEquals(LineDash.Dotted, restored.settingsOf(PenKind.Pencil).dash)
        assertEquals(LineDash.Dotted, restored.favorites.single { it.id == "pc" }.settings.dash)
        // Settings files from before INK-07 (no dash), unknown names and a dashed highlighter read as solid.
        val old = PenStore.restore(Toolbox(), PenPreferences(pens = listOf(PenState("Fountain", custom, 5f), PenState("Brush", custom, 5f, dash = "Wavy"), PenState("Highlighter", custom, 5f, dash = "Dashed"))))
        assertEquals(LineDash.Solid, old.settingsOf(PenKind.Fountain).dash)
        assertEquals(LineDash.Solid, old.settingsOf(PenKind.Brush).dash)
        assertEquals(LineDash.Solid, old.settingsOf(PenKind.Highlighter).dash)
    }
}
