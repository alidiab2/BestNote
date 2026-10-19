package app.bestnote.feature.editor

/**
 * REQ-006: the page a notebook reopens on, kept as a tiny notebook extra (like the new-pages paper).
 * The page scroll lives on the canvas view, which is rebuilt every time the editor is entered, so
 * without this the view always starts on the first page.
 */
internal object LastPage {
    /** Name of the notebook extra that keeps the last page viewed. */
    const val EXTRA = "lastpage.json"

    /** The stored form of the last page viewed: a tiny JSON object. Notebooks written before it simply have no such extra. */
    fun encode(page: Int): ByteArray = """{"page":${page.coerceAtLeast(0)}}""".encodeToByteArray()

    /** Reads [encode]; 0 for nothing stored or anything unusable (never throws). */
    fun decode(bytes: ByteArray?): Int =
        bytes?.decodeToString()?.let { Regex("\"page\"\\s*:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
}
