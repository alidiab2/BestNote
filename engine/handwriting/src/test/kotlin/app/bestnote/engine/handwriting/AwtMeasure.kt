package app.bestnote.engine.handwriting

import app.bestnote.engine.layout.HandFont
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.File

/** [TextMeasure] with the real bundled fonts through java.awt, so tests measure what Android draws. */
internal object AwtMeasure : TextMeasure {
    private fun file(f: HandFont): File? {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        return when (f) {
            HandFont.Caveat -> File(root, "core/designsystem/src/main/res/font/caveat.ttf")
            HandFont.ArefRuqaa -> File(root, "core/designsystem/src/main/res/font/aref_ruqaa.ttf")
            HandFont.GveretLevin -> File(root, "core/designsystem/src/main/res/font/gveret_levin.ttf")
            else -> null
        }
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

    override fun ascent(font: HandFont): Float = font(font).deriveFont(100f).getLineMetrics("Hg", frc).ascent / 100f

    override fun descent(font: HandFont): Float = font(font).deriveFont(100f).getLineMetrics("Hg", frc).descent / 100f
}
