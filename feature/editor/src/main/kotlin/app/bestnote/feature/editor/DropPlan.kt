package app.bestnote.feature.editor

/** One item of a dropped clip: its [mime] type (or null), the content [uri] if any, and its plain [text] if any. */
internal data class DropItem(val mime: String?, val uri: String?, val text: String?)

/** What to do with one dropped item. */
internal sealed interface DropAction {
    data class Picture(val uri: String) : DropAction
    data class Words(val text: String) : DropAction
}

/** MEDIA-01 drag-and-drop: turns what another app dropped into page actions (pure, so it is unit tested). */
internal object DropPlan {
    /** At most this many items from one drop, so a huge selection cannot flood the page. */
    const val MAX_ITEMS = 8

    /**
     * [clipMimes] are the clip's declared types. A content item is a picture if its own type, or (when it has none)
     * the clip's, is `image/*`. Items with only text become text boxes; anything else (PDF, audio, files) is ignored.
     */
    fun plan(items: List<DropItem>, clipMimes: List<String>): List<DropAction> {
        val clipHasImage = clipMimes.any { it.startsWith("image/") }
        val out = ArrayList<DropAction>()
        for (item in items) {
            if (out.size >= MAX_ITEMS) break
            val isImage = item.uri != null && (item.mime?.startsWith("image/") ?: clipHasImage)
            when {
                isImage -> out += DropAction.Picture(item.uri!!)
                item.uri == null && !item.text.isNullOrBlank() -> out += DropAction.Words(item.text.trim())
                // A link or file that is not a picture, with text alongside: keep the words.
                item.uri != null && !item.text.isNullOrBlank() && item.mime?.startsWith("text/") == true -> out += DropAction.Words(item.text.trim())
            }
        }
        return out
    }
}
