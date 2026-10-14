package app.bestnote.engine.layout

import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.InkSemantic

/**
 * Reads the lecturer's colour intent. Board conventions vary, but most follow the same pattern:
 * dark marker or white chalk explains, red marks what must be learned, green adds side remarks,
 * yellow chalk or purple highlights structure.
 */
object ColorSemantics {
    private val names = mapOf(
        "black" to InkSemantic.Regular, "blue" to InkSemantic.Regular, "navy" to InkSemantic.Regular,
        "white" to InkSemantic.Regular, "gray" to InkSemantic.Regular, "grey" to InkSemantic.Regular,
        "red" to InkSemantic.Essential, "orange" to InkSemantic.Essential, "pink" to InkSemantic.Essential,
        "magenta" to InkSemantic.Essential,
        "green" to InkSemantic.Annotation,
        "yellow" to InkSemantic.Emphasis, "purple" to InkSemantic.Emphasis, "violet" to InkSemantic.Emphasis,
    )

    /** The model's own reading of intent wins; the raw board colour is the fallback. */
    fun resolve(modelSemantic: String?, boardColor: String?): InkSemantic =
        parseSemantic(modelSemantic) ?: fromBoardColor(boardColor)

    fun parseSemantic(s: String?): InkSemantic? = when (s?.trim()?.lowercase()) {
        "regular", "normal", "explanation" -> InkSemantic.Regular
        "essential", "key", "important" -> InkSemantic.Essential
        "annotation", "remark", "aside", "note" -> InkSemantic.Annotation
        "emphasis", "highlight", "structure" -> InkSemantic.Emphasis
        else -> null
    }

    fun fromBoardColor(color: String?): InkSemantic {
        val c = color?.trim()?.lowercase() ?: return InkSemantic.Regular
        names[c]?.let { return it }
        names.entries.firstOrNull { c.contains(it.key) }?.let { return it.value } // "dark red", "chalk yellow"
        val rgb = c.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) ?: return InkSemantic.Regular
        return fromHsv(rgb)
    }

    private fun fromHsv(rgb: Int): InkSemantic {
        val r = (rgb shr 16 and 0xFF) / 255f
        val g = (rgb shr 8 and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        // Black, grey and white: no colour intent.
        if (max < 0.2f || delta / max < 0.25f) return InkSemantic.Regular
        val hue = when (max) {
            r -> 60f * (((g - b) / delta).mod(6f))
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return when {
            hue < 35f || hue >= 330f -> InkSemantic.Essential // red, orange, pink
            hue < 70f -> InkSemantic.Emphasis // yellow
            hue < 170f -> InkSemantic.Annotation // green
            hue < 255f -> InkSemantic.Regular // blue marker
            else -> InkSemantic.Emphasis // purple, magenta
        }
    }
}

/**
 * The notebook's coherent palette. Board colours never reach the page directly. They go through
 * [InkSemantic], so every reconstructed note looks like it came from the same careful student.
 */
object NotebookPalette {
    const val INK = 0xFF1F1F1FL
    const val ESSENTIAL = 0xFFC62828L
    const val ANNOTATION = 0xFF2E7D32L
    const val EMPHASIS = 0xFF1565C0L

    fun ink(s: InkSemantic): Long = when (s) {
        InkSemantic.Regular -> INK
        InkSemantic.Essential -> ESSENTIAL
        InkSemantic.Annotation -> ANNOTATION
        InkSemantic.Emphasis -> EMPHASIS
    }

    /** Callout tone for content whose type didn't name one. */
    fun defaultTone(s: InkSemantic): CalloutTone = when (s) {
        InkSemantic.Essential -> CalloutTone.KeyResult
        InkSemantic.Annotation -> CalloutTone.Example
        else -> CalloutTone.Info
    }
}
