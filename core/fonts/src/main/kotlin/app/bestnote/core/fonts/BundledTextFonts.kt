package app.bestnote.core.fonts

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import app.bestnote.core.designsystem.R
import app.bestnote.core.designsystem.font.HandwritingTypefaces

/**
 * TEXT-05: registers the bundled text fonts ([BundledFonts.text]) with [HandwritingTypefaces], so text boxes, the
 * PDF export and the previews resolve them by id. Variable fonts get a real bold instance (wght 700), so Bold is
 * not a smeared regular. Missing glyphs fall back to the system font of the same kind.
 */
internal object BundledTextFonts {
    private class Spec(val res: Int, val variable: Boolean, val bold: Int? = null, val generic: String = "sans-serif")

    private val specs = mapOf(
        "instrument-sans" to Spec(R.font.instrument_sans, variable = true),
        "fraunces" to Spec(R.font.fraunces, variable = true, generic = "serif"),
        "lora" to Spec(R.font.lora, variable = true, generic = "serif"),
        "jetbrains-mono" to Spec(R.font.jetbrains_mono, variable = true, generic = "monospace"),
        "ibm-plex-sans-arabic" to Spec(R.font.ibm_plex_sans_arabic_regular, variable = false, bold = R.font.ibm_plex_sans_arabic_semibold),
        "noto-naskh-arabic" to Spec(R.font.noto_naskh_arabic, variable = true, generic = "serif"),
        "heebo" to Spec(R.font.heebo, variable = true),
        "frank-ruhl-libre" to Spec(R.font.frank_ruhl_libre, variable = true, generic = "serif"),
    )

    fun register(res: Resources) {
        for ((id, spec) in specs) {
            if (HandwritingTypefaces.isRegistered(id)) continue
            runCatching { HandwritingTypefaces.register(id, build(res, spec)) }
        }
    }

    private fun build(res: Resources, spec: Spec): Typeface {
        val regular = Font.Builder(res, spec.res).setWeight(FontStyle.FONT_WEIGHT_NORMAL).apply {
            if (spec.variable) setFontVariationSettings("'wght' 400")
        }.build()
        val family = FontFamily.Builder(regular)
        val bold = when {
            spec.bold != null -> Font.Builder(res, spec.bold).setWeight(FontStyle.FONT_WEIGHT_BOLD).build()
            spec.variable -> Font.Builder(res, spec.res).setWeight(FontStyle.FONT_WEIGHT_BOLD).setFontVariationSettings("'wght' 700").build()
            else -> null
        }
        bold?.let(family::addFont)
        return Typeface.CustomFallbackBuilder(family.build()).setSystemFallback(spec.generic).build()
    }
}
