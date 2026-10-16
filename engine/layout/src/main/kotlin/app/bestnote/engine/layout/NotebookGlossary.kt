package app.bestnote.engine.layout

/**
 * The glossary of a whole notebook: the terms of every Smart Board page, in the order they were
 * first met, each once. Two spellings of a term that differ only in case, vowel points (Hebrew
 * niqqud, Arabic tashkeel), tatweel, punctuation or spacing are the same term; the first
 * translation met is kept.
 */
data class NotebookGlossary(val terms: List<GlossaryTerm> = emptyList()) {
    /** The glossary with [incoming] added, and the terms that were new. */
    fun merge(incoming: List<GlossaryTerm>): Pair<NotebookGlossary, List<GlossaryTerm>> {
        val seen = terms.mapTo(HashSet()) { key(it.term) }
        val added = ArrayList<GlossaryTerm>()
        for (t in incoming) {
            val term = t.term.trim()
            val translation = t.translation.trim()
            if (term.isEmpty() || translation.isEmpty()) continue
            if (seen.add(key(term))) added += GlossaryTerm(term, translation)
        }
        return NotebookGlossary(terms + added) to added
    }

    /** The terms of [incoming] the notebook does not have yet (deduplicated among themselves too). */
    fun newTerms(incoming: List<GlossaryTerm>): List<GlossaryTerm> = merge(incoming).second

    operator fun contains(term: String) = terms.any { key(it.term) == key(term) }

    companion object {
        private val MARKS = Regex("[\\u0591-\\u05C7\\u064B-\\u065F\\u0670\\u0640]")
        private val PUNCT = Regex("[\\p{P}\\p{S}&&[^+\\-=^_]]")
        private val SPACE = Regex("\\s+")

        /** The comparison key of a term. */
        fun key(term: String): String =
            term.replace(MARKS, "").replace(PUNCT, " ").replace(SPACE, " ").trim().lowercase()
    }
}
