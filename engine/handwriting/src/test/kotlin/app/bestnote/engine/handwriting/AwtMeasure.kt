package app.bestnote.engine.handwriting

import app.bestnote.engine.layout.HandFont
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File

/** [TextMeasure] with the real bundled fonts through java.awt, so tests measure what Android draws. */
internal object AwtMeasure : TextMeasure {
    private fun file(f: HandFont): File? {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val name = when (f.id) {
            "ibm-plex-sans-arabic" -> "ibm_plex_sans_arabic_regular"
            else -> f.id.replace('-', '_')
        }
        // The bundled hands and the TEXT-05 text fonts, by registry id.
        return File(root, "core/designsystem/src/main/res/font/$name.ttf").takeIf { it.exists() }
    }

    private val fonts = HashMap<HandFont, Font>()

    fun font(f: HandFont): Font = fonts.getOrPut(f) { file(f)?.let { Font.createFont(Font.TRUETYPE_FONT, it) } ?: Font(Font.SANS_SERIF, Font.PLAIN, 1) }

    private val frc = FontRenderContext(null, true, true)

    override fun width(text: String, font: HandFont, sizePt: Float): Float {
        val f = font(font).deriveFont(sizePt)
        // Characters the font lacks are drawn by a fallback font on Android; measure them with a default font here.
        val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(sizePt)
        var w = 0f
        val run = StringBuilder()
        var own = true
        fun flush() {
            if (run.isEmpty()) return
            w += (if (own) f else fallback).getStringBounds(run.toString(), frc).width.toFloat()
            run.clear()
        }
        for (c in text) {
            val has = f.canDisplay(c)
            if (has != own) { flush(); own = has }
            run.append(c)
        }
        flush()
        return w
    }

    private val inkCache = HashMap<Pair<String, HandFont>, FloatArray>()

    override fun ink(text: String, font: HandFont, sizePt: Float): FloatArray {
        val unit = inkCache.getOrPut(text to font) {
            val f = font(font).deriveFont(100f)
            val fallback = Font(Font.SANS_SERIF, Font.PLAIN, 100)
            val s = java.text.AttributedString(text.ifEmpty { " " })
            for ((i, c) in text.withIndex()) s.addAttribute(java.awt.font.TextAttribute.FONT, if (f.canDisplay(c)) f else fallback, i, i + 1)
            if (text.isEmpty()) s.addAttribute(java.awt.font.TextAttribute.FONT, f)
            val l = java.awt.font.TextLayout(s.iterator, frc)
            val b = l.bounds
            if (b.isEmpty) floatArrayOf(0f, 0f, 0f, 0f) else floatArrayOf((-b.y / 100).toFloat(), ((b.y + b.height) / 100).toFloat(), (-b.x / 100).toFloat(), ((b.x + b.width - l.advance) / 100).toFloat())
        }
        return FloatArray(4) { unit[it] * sizePt }
    }

    override fun ascent(font: HandFont): Float = font(font).deriveFont(100f).getLineMetrics("Hg", frc).ascent / 100f

    override fun descent(font: HandFont): Float = font(font).deriveFont(100f).getLineMetrics("Hg", frc).descent / 100f
}
