package app.bestnote.feature.smartboard

import android.graphics.Paint
import android.graphics.Typeface
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.handwriting.TypeRun
import app.bestnote.engine.handwriting.TypesetPage
import app.bestnote.engine.layout.HandFont

/** [TextMeasure] with the bundled handwriting typefaces, exactly as the page renderer draws them. */
internal class PaintMeasure : TextMeasure {
    private val paints = HashMap<HandFont, Paint>()
    private val cache = HashMap<Triple<String, HandFont, Int>, Float>()

    private fun paint(f: HandFont): Paint = paints.getOrPut(f) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = HandwritingTypefaces.of(fontId(f)) ?: Typeface.DEFAULT
            textSize = REF
        }
    }

    override fun width(text: String, font: HandFont, sizePt: Float): Float =
        cache.getOrPut(Triple(text, font, 0)) { paint(font).measureText(text) / REF } * sizePt

    override fun ascent(font: HandFont): Float = -paint(font).fontMetrics.ascent / REF

    override fun descent(font: HandFont): Float = paint(font).fontMetrics.descent / REF

    companion object {
        private const val REF = 100f

        fun fontId(f: HandFont) = when (f) {
            HandFont.Caveat -> HandwritingTypefaces.LATIN_FIRST
            HandFont.ArefRuqaa -> HandwritingTypefaces.ARABIC_FIRST
        }
    }
}

/**
 * Typed handwriting → editor elements: one [TextElement] per run, its box placed so the text's
 * baseline lands exactly on the ruled line ([TextElement]s lay out their first line at the top
 * plus the font's ascent).
 */
internal object TypesetElements {
    fun elements(page: TypesetPage, measure: TextMeasure, newId: () -> String): List<PageElement> = page.runs.map { element(it, measure, newId()) }

    private fun element(r: TypeRun, measure: TextMeasure, id: String): TextElement {
        val pad = r.sizePt * PAD
        val width = r.width + 2 * pad
        val ascent = measure.ascent(r.font) * r.sizePt
        val height = (measure.ascent(r.font) + measure.descent(r.font)) * r.sizePt * LINE
        val top = r.baseline - ascent
        return TextElement(
            id = id,
            cx = r.left - pad + width / 2,
            cy = top + height / 2,
            width = width,
            height = height,
            text = r.text,
            sizePt = r.sizePt.coerceIn(TextElement.MIN_SIZE_PT, TextElement.MAX_SIZE_PT),
            colorArgb = r.color,
            // Start = left for Latin, right for an Arabic run (the box is its exact width either way).
            align = TextAlignment.Start,
            font = PaintMeasure.fontId(r.font),
        )
    }

    private const val PAD = 0.12f
    private const val LINE = 1.12f
}
