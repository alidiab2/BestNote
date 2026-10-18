package app.bestnote.feature.editor

import app.bestnote.core.datastore.FavoritePen
import app.bestnote.core.datastore.PenPreferences
import app.bestnote.core.datastore.PenState
import app.bestnote.core.model.LineDash

/**
 * Toolbox <-> settings file (INK-04..06): every pen's color, size, opacity, pressure sensitivity,
 * stabilization and line dash, the saved custom colors and the favorite pens survive restarts. Session state (the
 * pen in hand, lasso, shapes, recent color dots) is not persisted.
 */
internal object PenStore {
    fun toPrefs(toolbox: Toolbox): PenPreferences = PenPreferences(
        pens = PenKind.entries.filter { it.inks }.map { state(it, toolbox.settingsOf(it)) },
        savedColors = toolbox.savedColors,
        favorites = toolbox.favorites.map { FavoritePen(it.id, state(it.kind, it.settings)) },
    )

    /** [toolbox] with the saved pens, colors and favorites applied. Unknown pen names are skipped. */
    fun restore(toolbox: Toolbox, prefs: PenPreferences): Toolbox {
        val saved = prefs.pens.mapNotNull { s -> kind(s.pen)?.takeIf { it.inks }?.let { it to settings(it, s) } }
        var box = toolbox.copy(
            settings = toolbox.settings + saved,
            savedColors = prefs.savedColors,
            favorites = prefs.favorites.mapNotNull { f -> kind(f.state.pen)?.takeIf { it.inks }?.let { PenFavorite(f.id, it, settings(it, f.state)) } },
        )
        // Each pen's saved color is one of its quick dots, as if just picked.
        for ((kind, s) in saved) {
            val keep = box.settingsOf(kind)
            box = box.withColor(s.colorArgb, kind).let { it.copy(settings = it.settings + (kind to keep)) }
        }
        return box
    }

    private fun kind(name: String): PenKind? = PenKind.entries.firstOrNull { it.name == name }

    private fun state(kind: PenKind, s: PenSettings) =
        PenState(kind.name, s.colorArgb, s.size, s.opacity, s.pressureSensitivity, s.stabilization, s.dash.name)

    private fun settings(kind: PenKind, s: PenState): PenSettings {
        val range = PenDefaults.sizeRange(kind)
        val dash = LineDash.entries.firstOrNull { it.name == s.dash && kind.dashes } ?: LineDash.Solid
        return PenSettings(s.colorArgb, s.sizePx.coerceIn(range.start, range.endInclusive), s.opacity, s.pressureSensitivity, s.stabilization, dash)
    }
}
