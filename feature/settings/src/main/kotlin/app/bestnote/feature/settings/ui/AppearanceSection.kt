package app.bestnote.feature.settings.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.bestnote.core.datastore.AppearancePreferences
import app.bestnote.core.datastore.ChromeWarmth
import app.bestnote.core.datastore.ThemeMode
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.data.AppLanguage

@Composable
internal fun AppearanceSection(
    prefs: UserPreferences,
    language: AppLanguage,
    languageSupported: Boolean,
    update: ((AppearancePreferences) -> AppearancePreferences) -> Unit,
    updateRoot: ((UserPreferences) -> UserPreferences) -> Unit,
    setLanguage: (AppLanguage) -> Unit,
) {
    val appearance = prefs.appearance
    DetailHeader(stringResource(R.string.settings_cat_appearance), stringResource(R.string.settings_appearance_desc))

    SettingsGroup(stringResource(R.string.settings_theme)) {
        row {
            Row(Modifier.fillMaxWidth().padding(Spacing.L), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                ThemeMode.entries.forEach { mode ->
                    ThemeCard(mode, prefs.themeMode == mode, appearance.chromeWarmth, Modifier.weight(1f)) {
                        updateRoot { it.copy(themeMode = mode) }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            row {
                SwitchRow(
                    stringResource(R.string.settings_dynamic_color),
                    prefs.dynamicColor,
                    { v -> updateRoot { it.copy(dynamicColor = v) } },
                    subtitle = stringResource(R.string.settings_dynamic_color_sub),
                    icon = Icons.Outlined.Wallpaper,
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_warmth)) {
        row {
            Column(Modifier.fillMaxWidth().padding(Spacing.L)) {
                Text(stringResource(R.string.settings_warmth_sub), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                Row(Modifier.fillMaxWidth().padding(top = Spacing.M), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    ChromeWarmth.entries.forEach { warmth ->
                        WarmthCard(warmth, warmth == appearance.chromeWarmth, Modifier.weight(1f)) {
                            update { it.copy(chromeWarmth = warmth) }
                        }
                    }
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_language)) {
        AppLanguage.entries.forEach { lang ->
            row {
                RadioRow(
                    title = when (lang) {
                        AppLanguage.System -> stringResource(R.string.settings_lang_system)
                        AppLanguage.English -> stringResource(R.string.settings_lang_english)
                        AppLanguage.Arabic -> stringResource(R.string.settings_lang_arabic)
                    },
                    subtitle = when (lang) {
                        AppLanguage.System -> stringResource(
                            if (languageSupported) R.string.settings_lang_system_sub else R.string.settings_lang_unsupported,
                        )
                        AppLanguage.Arabic -> stringResource(R.string.settings_lang_arabic_sub)
                        AppLanguage.English -> null
                    },
                    icon = if (lang == AppLanguage.System) Icons.Outlined.Language else null,
                    selected = lang == language,
                    enabled = languageSupported || lang == AppLanguage.System,
                    onSelect = { setLanguage(lang) },
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_motion_group)) {
        row {
            SwitchRow(
                stringResource(R.string.settings_reduce_motion),
                appearance.reduceMotion,
                { v -> update { it.copy(reduceMotion = v) } },
                subtitle = stringResource(R.string.settings_reduce_motion_sub),
                icon = Icons.Outlined.Animation,
            )
        }
    }
}

/** Chrome tones used by the mini previews (raw tones: these depict the themes, they aren't themed). */
private object Preview {
    val LightPaper = Color(0xFFF6F4EF)
    val LightSidebar = Color(0xFFEFECE5)
    val DarkPaper = Color(0xFF121417)
    val DarkSidebar = Color(0xFF1C1F24)
    val Page = Color(0xFFFFFEFB)
    val Accent = Color(0xFF2A4BD7)
}

private fun Color.neutral(warmth: ChromeWarmth): Color {
    val l = luminance()
    val v = if (l <= 0.0031308f) l * 12.92f else 1.055f * Math.pow(l.toDouble(), 1 / 2.4).toFloat() - 0.055f
    return lerp(Color(v, v, v), this, warmth.amount)
}

@Composable
private fun ThemeCard(mode: ThemeMode, selected: Boolean, warmth: ChromeWarmth, modifier: Modifier, onClick: () -> Unit) {
    val label = when (mode) {
        ThemeMode.System -> stringResource(R.string.settings_theme_system)
        ThemeMode.Light -> stringResource(R.string.settings_theme_light)
        ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
    }
    Column(
        modifier.clip(BestNoteShapes.Control).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(Spacing.XS),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(MockHeight)
                .clip(BestNoteShapes.Control)
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, BestNoteShapes.Control),
        ) {
            when (mode) {
                ThemeMode.Light -> MockHalf(false, warmth, Modifier.weight(1f))
                ThemeMode.Dark -> MockHalf(true, warmth, Modifier.weight(1f))
                ThemeMode.System -> {
                    MockHalf(false, warmth, Modifier.weight(1f))
                    MockHalf(true, warmth, Modifier.weight(1f))
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.S, start = Spacing.XXS),
        )
    }
}

@Composable
private fun MockHalf(dark: Boolean, warmth: ChromeWarmth, modifier: Modifier) {
    val bg = (if (dark) Preview.DarkPaper else Preview.LightPaper).neutral(warmth)
    val side = (if (dark) Preview.DarkSidebar else Preview.LightSidebar).neutral(warmth)
    Row(modifier.fillMaxHeight().background(bg)) {
        Box(Modifier.width(MockRail).fillMaxHeight().background(side))
        Column(Modifier.padding(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
            Box(Modifier.size(width = 26.dp, height = 34.dp).clip(BestNoteShapes.NotebookCover).background(Preview.Accent))
            Box(Modifier.size(width = 22.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(if (dark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.35f)))
        }
        Box(Modifier.padding(top = Spacing.S, end = Spacing.S).size(width = 26.dp, height = 34.dp).clip(RoundedCornerShape(2.dp)).background(Preview.Page))
    }
}

@Composable
private fun WarmthCard(warmth: ChromeWarmth, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = when (warmth) {
        ChromeWarmth.Warm -> stringResource(R.string.settings_warmth_warm)
        ChromeWarmth.Soft -> stringResource(R.string.settings_warmth_soft)
        ChromeWarmth.Neutral -> stringResource(R.string.settings_warmth_neutral)
    }
    Column(modifier.clip(BestNoteShapes.Control).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(Spacing.XS)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(SwatchHeight)
                .clip(BestNoteShapes.Control)
                .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, BestNoteShapes.Control),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Preview.LightSidebar.neutral(warmth)))
            Box(Modifier.weight(2f).fillMaxHeight().background(Preview.LightPaper.neutral(warmth)))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = Spacing.S, start = Spacing.XXS),
        )
    }
}

private val MockHeight = 72.dp
private val MockRail = 10.dp
private val SwatchHeight = 44.dp
