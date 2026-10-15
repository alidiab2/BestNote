package app.bestnote.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.bestnote.core.designsystem.R

/*
 * Fonts are bundled in res/font (SIL OFL, subset; licenses in assets/licenses), so typography works
 * offline and on devices without Google Play services. Any glyph a family lacks (e.g. Arabic in
 * Instrument Sans) falls back per glyph to the system font. When the UI locale is Arabic,
 * [bestNoteTypography] swaps in IBM Plex Sans Arabic, which also covers Latin, so mixed strings match.
 */

private fun variable(res: Int, weight: FontWeight, vararg extra: FontVariation.Setting) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), *extra))

private val DisplayWeights = listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

/** Fraunces at a fixed optical size (its `opsz` axis, 9–144): real optical sizing per role. */
private fun fraunces(opticalSize: Float): FontFamily =
    FontFamily(DisplayWeights.map { variable(R.font.fraunces, it, FontVariation.Setting("opsz", opticalSize)) })

/** Font families of the Atelier direction. */
object BestNoteFonts {
    /** Fraunces for display sizes (opsz 40). */
    val Display: FontFamily = fraunces(40f)

    /** Fraunces for titles and headlines (opsz 22). */
    val Title: FontFamily = fraunces(22f)

    /** Instrument Sans: body, labels, buttons. */
    val Ui: FontFamily = FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { variable(R.font.instrument_sans, it) },
    )

    /** IBM Plex Sans Arabic: Arabic UI and text boxes (has Latin too). */
    val Arabic: FontFamily = FontFamily(
        Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    )

    /** Caveat: handwriting samples only (pen previews, onboarding). Never for UI text. */
    val Hand: FontFamily = FontFamily(
        variable(R.font.caveat, FontWeight.Normal),
        variable(R.font.caveat, FontWeight.SemiBold),
    )
}

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Float, lineHeight: Float, weight: FontWeight, tracking: Float = 0f) =
    TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        fontWeight = weight,
        letterSpacing = tracking.em,
        lineHeightStyle = trim,
    )

/**
 * Type scale (sp): display 40/1.1, title-large 25, title 19, body 15, label 13–14, caption 12.5,
 * overline 12 with 0.12em tracking. Material slots map as:
 * display* → Fraunces (opsz 40); headline*, titleLarge/Medium → Fraunces (opsz 22); titleSmall → UI;
 * body* / label* → UI family.
 */
fun bestNoteTypography(arabic: Boolean = false): Typography {
    val display = if (arabic) BestNoteFonts.Arabic else BestNoteFonts.Display
    val title = if (arabic) BestNoteFonts.Arabic else BestNoteFonts.Title
    val ui = if (arabic) BestNoteFonts.Arabic else BestNoteFonts.Ui
    return Typography(
        displayLarge = style(display, 48f, 52f, FontWeight.Light, -0.01f),
        displayMedium = style(display, 40f, 44f, FontWeight.Normal, -0.01f),
        displaySmall = style(display, 32f, 38f, FontWeight.Normal),
        headlineLarge = style(title, 28f, 34f, FontWeight.Normal),
        headlineMedium = style(title, 25f, 31f, FontWeight.Medium),
        headlineSmall = style(title, 22f, 28f, FontWeight.Medium),
        titleLarge = style(title, 24f, 30f, FontWeight.Medium),
        titleMedium = style(title, 19f, 25f, FontWeight.Medium),
        titleSmall = style(ui, 15f, 21f, FontWeight.SemiBold),
        bodyLarge = style(ui, 16f, 24f, FontWeight.Normal),
        bodyMedium = style(ui, 15f, 22f, FontWeight.Normal),
        bodySmall = style(ui, 12.5f, 17f, FontWeight.Normal),
        labelLarge = style(ui, 14f, 20f, FontWeight.Medium),
        labelMedium = style(ui, 13f, 18f, FontWeight.Medium),
        labelSmall = style(ui, 12f, 16f, FontWeight.SemiBold, 0.12f),
    )
}

/** Roles Material's Typography has no slot for. Read via [BestNoteTheme.type]. */
data class BestNoteTextStyles(
    /** 12.5 sp captions (thumbnail dates, metadata). */
    val caption: TextStyle,
    /** 12 sp uppercase-style section labels, 0.12em tracking. */
    val overline: TextStyle,
    /** Caveat, for handwriting samples only. */
    val handSample: TextStyle,
)

internal fun bestNoteTextStyles(arabic: Boolean = false): BestNoteTextStyles {
    val ui = if (arabic) BestNoteFonts.Arabic else BestNoteFonts.Ui
    return BestNoteTextStyles(
        caption = style(ui, 12.5f, 17f, FontWeight.Normal),
        overline = style(ui, 12f, 16f, FontWeight.SemiBold, 0.12f),
        handSample = style(BestNoteFonts.Hand, 26f, 30f, FontWeight.Normal),
    )
}
