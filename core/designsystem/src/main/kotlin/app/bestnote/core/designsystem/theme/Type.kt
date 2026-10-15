package app.bestnote.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.bestnote.core.designsystem.R
import androidx.compose.ui.text.googlefonts.Font as googleFont

/*
 * Fonts are downloadable Google Fonts (fetched once by Play services and cached system-wide), so
 * the APK stays small. While a font downloads, or on a device without Play services, Compose
 * renders with the platform default, and any glyph a family lacks (e.g. Arabic in Instrument
 * Sans) falls back per glyph to the system font. When the UI locale is Arabic, [bestNoteTypography]
 * swaps the UI family for IBM Plex Sans Arabic, which also covers Latin, so mixed strings match.
 */
private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun family(name: String, vararg weights: FontWeight): FontFamily =
    FontFamily(weights.map { googleFont(GoogleFont(name), provider, it) })

/** Font families of the Atelier direction. */
object BestNoteFonts {
    /**
     * Fraunces: display and titles. The provider serves static instances, so optical size is
     * approximated per role: large sizes use lighter weights, small titles heavier ones.
     */
    val Display: FontFamily = family("Fraunces", FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

    /** Instrument Sans: body, labels, buttons. */
    val Ui: FontFamily = family("Instrument Sans", FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

    /** IBM Plex Sans Arabic: Arabic UI and text boxes (has Latin too). */
    val Arabic: FontFamily = family("IBM Plex Sans Arabic", FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)

    /** Caveat: handwriting samples only (pen previews, onboarding). Never for UI text. */
    val Hand: FontFamily = family("Caveat", FontWeight.Normal, FontWeight.SemiBold)
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
 * display* / headline* → Fraunces; title* → Fraunces (Large, Medium) and UI (Small);
 * body* / label* → UI family.
 */
fun bestNoteTypography(arabic: Boolean = false): Typography {
    val display = if (arabic) BestNoteFonts.Arabic else BestNoteFonts.Display
    val ui = if (arabic) BestNoteFonts.Arabic else BestNoteFonts.Ui
    return Typography(
        displayLarge = style(display, 48f, 52f, FontWeight.Light, -0.01f),
        displayMedium = style(display, 40f, 44f, FontWeight.Normal, -0.01f),
        displaySmall = style(display, 32f, 38f, FontWeight.Normal),
        headlineLarge = style(display, 28f, 34f, FontWeight.Normal),
        headlineMedium = style(display, 25f, 31f, FontWeight.Medium),
        headlineSmall = style(display, 22f, 28f, FontWeight.Medium),
        titleLarge = style(display, 24f, 30f, FontWeight.Medium),
        titleMedium = style(display, 19f, 25f, FontWeight.Medium),
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
