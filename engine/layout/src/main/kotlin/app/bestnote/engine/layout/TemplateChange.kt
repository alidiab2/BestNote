package app.bestnote.engine.layout

/**
 * What switching from one template to another costs once the AI has answered: only the look changed
 * → the cached notes are re-drawn instantly (no AI call); the AI instructions or the allowed
 * elements changed → the board must be read again.
 */
enum class TemplateChange {
    Same, LookOnly, NeedsAi;

    companion object {
        fun between(old: NoteTemplate, new: NoteTemplate): TemplateChange = when {
            old == new -> Same
            old.ai == new.ai && old.elements == new.elements -> LookOnly
            else -> NeedsAi
        }
    }
}
