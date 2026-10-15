package app.bestnote.feature.settings.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.feature.settings.R

private const val GITHUB_URL = "https://github.com/alidiab2/BestNote"

internal fun appVersionName(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"

/** Bundled fonts (SIL OFL 1.1); license texts ship in `:core:designsystem` assets/licenses. */
private val FontLicenses = listOf(
    "Fraunces" to "licenses/OFL-Fraunces.txt",
    "Instrument Sans" to "licenses/OFL-InstrumentSans.txt",
    "IBM Plex Sans Arabic" to "licenses/OFL-IBMPlexSansArabic.txt",
    "Caveat" to "licenses/OFL-Caveat.txt",
)

/** Libraries in the app, all Apache License 2.0. */
private val ApacheLibraries = listOf(
    "AndroidX (Core, Activity, Lifecycle, Navigation, DataStore, Room)",
    "Jetpack Compose & Material 3",
    "androidx.ink, androidx.graphics, Motion Prediction",
    "Dagger Hilt",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization",
    "OkHttp",
)

@Composable
internal fun AboutSection(showLicenses: Boolean, onShowLicenses: (Boolean) -> Unit) {
    if (showLicenses) {
        LicensesPage(onBack = { onShowLicenses(false) })
        return
    }
    val context = LocalContext.current
    val version = remember { appVersionName(context) }
    DetailHeader(stringResource(R.string.settings_cat_about), stringResource(R.string.settings_about_desc))

    Column(
        Modifier
            .padding(top = Spacing.XL)
            .fillMaxWidth()
            .softShadow(Elevation.Low, BestNoteShapes.Card)
            .clip(BestNoteShapes.Card)
            .background(MaterialTheme.colorScheme.surface)
            .hairline(BestNoteShapes.Card)
            .padding(Spacing.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.settings_app_name), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
        Spacer(Modifier.size(Spacing.M))
        Text(
            stringResource(R.string.settings_about_tagline),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .clip(BestNoteShapes.Pill)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = Spacing.L, vertical = Spacing.S),
        )
    }

    SettingsGroup(stringResource(R.string.settings_project_group)) {
        row {
            SettingsRow(
                title = stringResource(R.string.settings_source_code),
                subtitle = stringResource(R.string.settings_source_code_sub),
                icon = Icons.Outlined.Code,
                modifier = Modifier.clickable(role = Role.Button) { context.startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri())) },
            ) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = BestNoteTheme.colors.muted) }
        }
        row {
            SettingsRow(
                title = stringResource(R.string.settings_licenses),
                subtitle = stringResource(R.string.settings_licenses_sub),
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                modifier = Modifier.clickable(role = Role.Button) { onShowLicenses(true) },
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = BestNoteTheme.colors.muted) }
        }
    }
}

@Composable
private fun LicensesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = onBack, modifier = Modifier.padding(bottom = Spacing.S)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(Spacing.S))
        Text(stringResource(R.string.settings_cat_about))
    }
    DetailHeader(stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_desc))

    SettingsGroup(stringResource(R.string.settings_licenses_fonts)) {
        FontLicenses.forEach { (name, asset) ->
            row { LicenseRow(name, stringResource(R.string.settings_license_ofl), asset, context) }
        }
    }
    SettingsGroup(stringResource(R.string.settings_licenses_libraries)) {
        ApacheLibraries.forEach { name ->
            row {
                SettingsRow(title = name, subtitle = stringResource(R.string.settings_license_apache))
            }
        }
    }
}

@Composable
private fun LicenseRow(name: String, license: String, asset: String, context: Context) {
    var open by rememberSaveable(asset) { mutableStateOf(false) }
    val reduced = reducedMotion()
    Column {
        SettingsRow(
            title = name,
            subtitle = license,
            icon = Icons.Outlined.FontDownload,
            modifier = Modifier.clickable(role = Role.Button) { open = !open },
        ) { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = BestNoteTheme.colors.muted) }
        AnimatedVisibility(open, enter = Motion.Enter.content(reduced), exit = Motion.Exit.content(reduced)) {
            val text = remember(asset) {
                runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("")
            }
            Row(Modifier.fillMaxWidth().padding(start = Spacing.L, end = Spacing.L, bottom = Spacing.L), horizontalArrangement = Arrangement.Start) {
                Text(
                    text,
                    style = BestNoteTheme.type.caption.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(BestNoteShapes.Control)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(Spacing.M),
                )
            }
        }
    }
}

