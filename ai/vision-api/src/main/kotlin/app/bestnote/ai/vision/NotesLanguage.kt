package app.bestnote.ai.vision

/**
 * The language Smart Board writes the notes in. [Board] (the default) keeps each block in the
 * board's own language; any other entry translates the notes into it, maths untouched.
 * [promptName] is what the model is told; [rtl] says the notes will read right to left.
 */
enum class NotesLanguage(val code: String?, val promptName: String?, val rtl: Boolean = false) {
    Board(null, null),
    English("en", "English"),
    Hebrew("he", "Hebrew", rtl = true),
    Arabic("ar", "Arabic", rtl = true),
    French("fr", "French"),
    Spanish("es", "Spanish"),
    German("de", "German"),
    Russian("ru", "Russian"),
    Turkish("tr", "Turkish"),
    ;

    companion object {
        fun of(code: String?): NotesLanguage = entries.firstOrNull { it.code == code && code != null } ?: Board
    }
}
