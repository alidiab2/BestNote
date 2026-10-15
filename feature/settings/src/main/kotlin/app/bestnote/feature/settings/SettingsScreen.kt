/**
 * :feature:settings — the Settings screen (SET-01): S Pen & input, gestures, writing defaults,
 * Smart Board key, appearance, storage and about. Two panes on tablets (categories | details), one
 * pane on phones. Preferences go through `:core:datastore`'s [UserPreferencesRepository]; the
 * Smart Board key goes through the Keystore-backed settings of `:ai:vision-gemini`.
 */
package app.bestnote.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.datastore.SideButtonAction
import app.bestnote.core.datastore.ThemeMode
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.UserPreferencesRepository
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.feature.settings.data.AppLanguage
import app.bestnote.feature.settings.ui.AboutSection
import app.bestnote.feature.settings.ui.AppearanceSection
import app.bestnote.feature.settings.ui.GesturesSection
import app.bestnote.feature.settings.ui.SettingsGroup
import app.bestnote.feature.settings.ui.SettingsRow
import app.bestnote.feature.settings.ui.SmartBoardSection
import app.bestnote.feature.settings.ui.StorageSection
import app.bestnote.feature.settings.ui.StylusSection
import app.bestnote.feature.settings.ui.TileSize
import app.bestnote.feature.settings.ui.WritingSection
import app.bestnote.feature.settings.ui.appVersionName
import app.bestnote.feature.settings.ui.formatBytes
import kotlinx.serialization.Serializable

@Serializable
data object SettingsRoute

fun NavGraphBuilder.settingsScreen(onBack: () -> Unit) {
    composable<SettingsRoute> { SettingsScreen(onBack) }
}

/** Width from which Settings shows categories and details side by side. */
private val TwoPaneMinWidth = 720.dp
private val NavPaneWidth = 320.dp
private val DetailMaxWidth = 760.dp

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    var showLicenses by rememberSaveable { mutableStateOf(false) }

    val summaries = categorySummaries(prefs, language, storage.usage?.totalBytes)
    val detail: @Composable (SettingsCategory) -> Unit = { category ->
        when (category) {
            SettingsCategory.Stylus -> StylusSection(prefs, viewModel::updateStylus, viewModel::update)
            SettingsCategory.Gestures -> GesturesSection(prefs.gestures, viewModel::updateGestures)
            SettingsCategory.Writing -> WritingSection(prefs, viewModel::updateWriting, viewModel::update)
            SettingsCategory.SmartBoard -> SmartBoardSection(hiltViewModel())
            SettingsCategory.Appearance -> AppearanceSection(
                prefs,
                language,
                viewModel.languageSupported,
                viewModel::updateAppearance,
                viewModel::update,
                viewModel::setLanguage,
            )
            SettingsCategory.Storage -> StorageSection(storage, prefs.storage, viewModel::updateStorage, viewModel::clearThumbnails)
            SettingsCategory.About -> AboutSection(showLicenses, onShowLicenses = { showLicenses = it })
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (maxWidth >= TwoPaneMinWidth) {
            val current = selected ?: SettingsCategory.Stylus
            BackHandler(enabled = showLicenses) { showLicenses = false }
            Row(Modifier.fillMaxSize()) {
                CategoryPane(
                    selected = current,
                    summaries = summaries,
                    onBack = onBack,
                    onSelect = {
                        showLicenses = false
                        viewModel.select(it)
                    },
                    modifier = Modifier.width(NavPaneWidth).fillMaxHeight().background(BestNoteTheme.colors.sidebar),
                )
                DetailPane(current, showLicenses, Modifier.weight(1f), detail)
            }
        } else {
            val current = selected
            BackHandler(enabled = current != null) {
                if (showLicenses) showLicenses = false else viewModel.select(null)
            }
            if (current == null) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.L)) {
                    PaneTitle(stringResource(R.string.settings_title), onBack)
                    SettingsGroup(label = null, modifier = Modifier.padding(bottom = Spacing.XL)) {
                        SettingsCategory.entries.forEach { category ->
                            row {
                                SettingsRow(
                                    title = stringResource(category.titleRes),
                                    subtitle = summaries[category],
                                    icon = category.icon,
                                    modifier = Modifier.selectable(false, role = Role.Button) { viewModel.select(category) },
                                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = BestNoteTheme.colors.muted) }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    PaneTitle(
                        stringResource(R.string.settings_title),
                        onBack = { if (showLicenses) showLicenses = false else viewModel.select(null) },
                        modifier = Modifier.padding(horizontal = Spacing.L),
                        compact = true,
                    )
                    DetailPane(current, showLicenses, Modifier.weight(1f), detail)
                }
            }
        }
    }
}

@Composable
private fun PaneTitle(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(
        modifier.fillMaxWidth().padding(top = Spacing.M, bottom = if (compact) 0.dp else Spacing.S),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
        }
        Spacer(Modifier.width(Spacing.XS))
        Text(
            title,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun CategoryPane(
    selected: SettingsCategory,
    summaries: Map<SettingsCategory, String>,
    onBack: () -> Unit,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.M + Spacing.XXS, vertical = Spacing.S)) {
        PaneTitle(stringResource(R.string.settings_title), onBack)
        Spacer(Modifier.size(Spacing.S))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
            SettingsCategory.entries.forEach { category ->
                if (category == SettingsCategory.Appearance || category == SettingsCategory.About) Spacer(Modifier.size(Spacing.S))
                CategoryItem(category, summaries[category], category == selected) { onSelect(category) }
            }
        }
        Spacer(Modifier.size(Spacing.XL))
        Text(
            stringResource(R.string.settings_about_tagline),
            style = BestNoteTheme.type.caption,
            color = BestNoteTheme.colors.muted,
            modifier = Modifier.padding(horizontal = Spacing.M),
        )
    }
}

@Composable
private fun CategoryItem(category: SettingsCategory, summary: String?, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tileBg = when {
        selected -> MaterialTheme.colorScheme.primary
        BestNoteTheme.colors.sidebar == MaterialTheme.colorScheme.surface -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surface
    }
    val tileFg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BestNoteShapes.Control)
            .background(container)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = Spacing.M - Spacing.XXS, vertical = Spacing.S + Spacing.XXS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
    ) {
        Box(
            Modifier.size(TileSize).clip(BestNoteShapes.Control).background(tileBg),
            contentAlignment = Alignment.Center,
        ) { Icon(category.icon, null, tint = tileFg, modifier = Modifier.size(19.dp)) }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(category.titleRes),
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(summary, style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailPane(
    category: SettingsCategory,
    showLicenses: Boolean,
    modifier: Modifier,
    content: @Composable (SettingsCategory) -> Unit,
) {
    val reduced = reducedMotion()
    AnimatedContent(
        targetState = category to showLicenses,
        transitionSpec = { Motion.Enter.content(reduced) togetherWith Motion.Exit.content(reduced) },
        modifier = modifier.fillMaxHeight(),
        label = "settingsDetail",
    ) { (shown, _) ->
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = DetailMaxWidth)
                    .fillMaxWidth()
                    .padding(PaddingValues(start = Spacing.XL, end = Spacing.XL, top = Spacing.XL, bottom = Spacing.XXL * 2)),
            ) { content(shown) }
        }
    }
}

private val SettingsCategory.icon: ImageVector
    get() = when (this) {
        SettingsCategory.Stylus -> Icons.Outlined.Draw
        SettingsCategory.Gestures -> Icons.Outlined.TouchApp
        SettingsCategory.Writing -> Icons.Outlined.Edit
        SettingsCategory.SmartBoard -> Icons.Outlined.AutoAwesome
        SettingsCategory.Appearance -> Icons.Outlined.Palette
        SettingsCategory.Storage -> Icons.Outlined.SdStorage
        SettingsCategory.About -> Icons.Outlined.Info
    }

internal val SettingsCategory.titleRes: Int
    get() = when (this) {
        SettingsCategory.Stylus -> R.string.settings_cat_stylus
        SettingsCategory.Gestures -> R.string.settings_cat_gestures
        SettingsCategory.Writing -> R.string.settings_cat_writing
        SettingsCategory.SmartBoard -> R.string.settings_cat_ai
        SettingsCategory.Appearance -> R.string.settings_cat_appearance
        SettingsCategory.Storage -> R.string.settings_cat_storage
        SettingsCategory.About -> R.string.settings_cat_about
    }

@Composable
private fun categorySummaries(prefs: UserPreferences, language: AppLanguage, storageBytes: Long?): Map<SettingsCategory, String> {
    val context = LocalContext.current
    val version = remember { appVersionName(context) }
    return mapOf(
        SettingsCategory.Stylus to when (prefs.stylus.sideButtonHold) {
            SideButtonAction.TemporaryEraser -> stringResource(R.string.settings_sum_stylus_eraser)
            SideButtonAction.Lasso -> stringResource(R.string.settings_sum_stylus_lasso)
            SideButtonAction.Off -> stringResource(R.string.settings_sum_stylus_off)
        },
        SettingsCategory.Gestures to stringResource(R.string.settings_sum_gestures),
        SettingsCategory.Writing to stringResource(R.string.settings_sum_writing),
        SettingsCategory.SmartBoard to stringResource(R.string.settings_sum_ai),
        SettingsCategory.Appearance to stringResource(
            R.string.settings_sum_theme,
            when (prefs.themeMode) {
                ThemeMode.System -> stringResource(R.string.settings_theme_system)
                ThemeMode.Light -> stringResource(R.string.settings_theme_light)
                ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
            },
        ) + when (language) {
            AppLanguage.System -> ""
            AppLanguage.English -> " · " + stringResource(R.string.settings_lang_english)
            AppLanguage.Arabic -> " · " + stringResource(R.string.settings_lang_arabic)
        },
        SettingsCategory.Storage to (storageBytes?.let { stringResource(R.string.settings_sum_storage, formatBytes(context, it)) } ?: "…"),
        SettingsCategory.About to stringResource(R.string.settings_sum_about, version),
    )
}
