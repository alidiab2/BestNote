package app.bestnote.ai.vision.providers

import android.content.Context
import android.content.SharedPreferences
import app.bestnote.ai.vision.NotesLanguage
import app.bestnote.core.model.EquationStyle
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.StyleTemplates

/** How Smart Board writes the notes: with the handwriting fonts (default) or as synthetic pen strokes. */
enum class WritingMode { Font, Strokes }

/** Minimal key-value storage, so the style rules are testable without Android. */
interface StyleStorage {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/**
 * The Smart Board design template per notebook, so every Smart Board page of a notebook looks the
 * same: the style last used in a notebook is preselected there next time; a notebook that never used
 * Smart Board starts from the app-wide default (Settings → Smart Board AI).
 */
class SmartBoardStyles(private val storage: StyleStorage) {
    constructor(context: Context) : this(SharedPreferencesStorage(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    var defaultStyle: StyleTemplate
        get() = StyleTemplates.byId(storage.get(KEY_DEFAULT))
        set(value) = storage.put(KEY_DEFAULT, value.id)

    fun styleFor(notebookId: String): StyleTemplate = storage.get(KEY_NOTEBOOK + notebookId)?.let(StyleTemplates::byId) ?: defaultStyle

    fun setStyleFor(notebookId: String, style: StyleTemplate) = storage.put(KEY_NOTEBOOK + notebookId, style.id)

    /** The template id (built-in or the user's) last used in a notebook; falls back to its style id, then the app default. */
    fun templateIdFor(notebookId: String): String = storage.get(KEY_TEMPLATE + notebookId) ?: styleFor(notebookId).id

    fun setTemplateIdFor(notebookId: String, templateId: String) {
        storage.put(KEY_TEMPLATE + notebookId, templateId)
        // Keep the older style key in step for built-ins.
        StyleTemplates.all.firstOrNull { it.id == templateId }?.let { setStyleFor(notebookId, it) }
    }

    /** Notes language for new notebooks: "same as the board" unless set in Settings. */
    var defaultLanguage: NotesLanguage
        get() = NotesLanguage.of(storage.get(KEY_LANGUAGE))
        set(value) = storage.put(KEY_LANGUAGE, value.code)

    /** The notes language last used in a notebook, saved with its style. */
    fun languageFor(notebookId: String): NotesLanguage =
        storage.get(KEY_NOTEBOOK_LANGUAGE + notebookId)?.let { if (it == BOARD) NotesLanguage.Board else NotesLanguage.of(it) } ?: defaultLanguage

    fun setLanguageFor(notebookId: String, language: NotesLanguage) = storage.put(KEY_NOTEBOOK_LANGUAGE + notebookId, language.code ?: BOARD)

    /** MATH-02: the notebook's own equation style, overriding its template's; null = follow the template. */
    fun equationStyleFor(notebookId: String): EquationStyle? = EquationStyle.entries.firstOrNull { it.name == storage.get(KEY_NOTEBOOK_EQUATIONS + notebookId) }

    fun setEquationStyleFor(notebookId: String, style: EquationStyle?) = storage.put(KEY_NOTEBOOK_EQUATIONS + notebookId, style?.name)

    var writingMode: WritingMode
        get() = WritingMode.entries.firstOrNull { it.name == storage.get(KEY_MODE) } ?: WritingMode.Font
        set(value) = storage.put(KEY_MODE, value.name)

    private class SharedPreferencesStorage(private val prefs: SharedPreferences) : StyleStorage {
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private companion object {
        const val PREFS = "smartboard_styles"
        const val KEY_DEFAULT = "default_style"
        const val KEY_MODE = "writing_mode"
        const val KEY_NOTEBOOK = "notebook:"
        const val KEY_TEMPLATE = "notebook_template:"
        const val KEY_LANGUAGE = "default_language"
        const val KEY_NOTEBOOK_LANGUAGE = "notebook_language:"
        const val KEY_NOTEBOOK_EQUATIONS = "notebook_equations:"
        const val BOARD = "board"
    }
}
