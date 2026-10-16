package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiBlock
import app.bestnote.ai.vision.AiDocument

/**
 * A board photographed in several parts (left half, right half, the next panel…) read photo by
 * photo, merged into one set of notes in photo order: each photo's blocks keep their own teaching
 * order after the previous photo's, ids and margin-note anchors stay unique, and a later photo's
 * own title becomes a section heading.
 */
object BoardMerge {
    fun merge(docs: List<AiDocument>): AiDocument {
        if (docs.size <= 1) return docs.firstOrNull() ?: AiDocument()
        val title = docs.firstNotNullOfOrNull { it.title?.takeIf(String::isNotBlank) }
        val blocks = ArrayList<AiBlock>()
        var next = 0
        for ((p, doc) in docs.withIndex()) {
            val prefix = if (p == 0) "" else "p${p + 1}_"
            val own = doc.title?.takeIf { it.isNotBlank() && it != title }
            if (own != null) blocks += AiBlock(type = "heading", order = next++, level = 2, text = own, semantic = "emphasis")
            val orders = doc.blocks.mapIndexed { i, b -> b.order ?: i }
            val rank = orders.distinct().sorted().withIndex().associate { (i, o) -> o to i }
            val base = next
            for ((i, b) in doc.blocks.withIndex()) {
                blocks += b.copy(
                    order = base + rank.getValue(orders[i]),
                    id = b.id?.let { prefix + it },
                    annotates = b.annotates?.let { prefix + it },
                )
            }
            next = base + rank.size
        }
        return AiDocument(title, blocks)
    }
}
