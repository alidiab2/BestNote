package app.bestnote.feature.settings.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import app.bestnote.feature.settings.SmartBoardNotesViewModel
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.feature.settings.SmartBoardUiState
import app.bestnote.ai.vision.providers.AiProviderDefaults
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.reducedMotion
import app.bestnote.feature.settings.R
import app.bestnote.feature.settings.SmartBoardViewModel
import app.bestnote.feature.settings.data.KeyTestResult


@Composable
internal fun SmartBoardSection(viewModel: SmartBoardViewModel, onOpenTemplates: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DetailHeader(stringResource(R.string.settings_cat_ai), stringResource(R.string.settings_ai_desc))

    SettingsGroup(stringResource(R.string.settings_templates_group)) {
        row {
            SettingsRow(
                title = stringResource(R.string.settings_templates),
                subtitle = stringResource(R.string.settings_templates_sub),
                icon = androidx.compose.material.icons.Icons.Outlined.Palette,
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpenTemplates),
            ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
        }
    }

    SettingsGroup(stringResource(R.string.settings_ai_provider)) {
        AiProviderKind.entries.forEach { provider ->
            row {
                RadioRow(
                    title = provider.label(),
                    selected = provider == state.provider,
                    onSelect = { viewModel.selectProvider(provider) },
                    subtitle = provider.subtitle(),
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_ai_key_group)) {
        row {
            SettingsRow(
                title = if (state.stored.hasKey) stringResource(R.string.settings_ai_key_saved) else stringResource(R.string.settings_ai_key_none),
                subtitle = state.stored.hint?.let { stringResource(R.string.settings_ai_key_hint, it) }
                    ?: stringResource(if (state.compatible) R.string.settings_ai_key_optional_sub else R.string.settings_ai_key_none_sub, state.provider.label()),
                icon = Icons.Outlined.Key,
            ) {
                if (state.stored.hasKey) RowAction(stringResource(R.string.settings_ai_key_remove), viewModel::removeKey)
            }
        }
        row {
            Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                if (state.compatible) {
                    OutlinedTextField(
                        value = state.baseUrlDraft,
                        onValueChange = viewModel::onBaseUrlChange,
                        label = { Text(stringResource(R.string.settings_ai_base_url)) },
                        supportingText = { Text(stringResource(R.string.settings_ai_base_url_sub)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        shape = BestNoteShapes.Control,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        AiProviderDefaults.compatiblePresets.forEach { (name, url) ->
                            FilterChip(selected = state.baseUrlDraft == url, onClick = { viewModel.onBaseUrlChange(url) }, label = { Text(name) })
                        }
                    }
                }
                OutlinedTextField(
                    value = state.keyDraft,
                    onValueChange = viewModel::onKeyChange,
                    label = { Text(stringResource(if (state.stored.hasKey) R.string.settings_ai_key_replace else R.string.settings_ai_key_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    shape = BestNoteShapes.Control,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    OutlinedButton(onClick = viewModel::test, enabled = state.canTest) {
                        if (state.testing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.settings_ai_testing), Modifier.padding(start = Spacing.S))
                        } else {
                            Text(stringResource(R.string.settings_ai_test))
                        }
                    }
                    Button(onClick = viewModel::save, enabled = state.canSave) { Text(stringResource(R.string.settings_ai_save)) }
                }
                TestResult(state.result, state.justSaved)
            }
        }
        AiProviderDefaults.keyUrl(state.provider)?.let { url ->
            row {
                SettingsRow(
                    title = stringResource(R.string.settings_ai_get_key),
                    subtitle = url.removePrefix("https://"),
                    modifier = Modifier.clickable(role = Role.Button) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    },
                ) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = BestNoteTheme.colors.muted) }
            }
        }
    }

    ModelPicker(state, viewModel)

    NotesDefaults()

    SettingsGroup(stringResource(R.string.settings_ai_privacy_group)) {
        row {
            Row(Modifier.fillMaxWidth().padding(Spacing.L), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                IconTile(Icons.Outlined.Shield, tint = BestNoteTheme.colors.success, background = BestNoteTheme.colors.success.copy(alpha = 0.12f))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_ai_privacy_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.settings_ai_privacy_body), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                }
            }
        }
    }
}

@Composable
private fun TestResult(result: KeyTestResult?, justSaved: Boolean) {
    val reduced = reducedMotion()
    AnimatedVisibility(visible = result != null || justSaved, enter = Motion.Enter.content(reduced), exit = Motion.Exit.content(reduced)) {
        val ok = result == null || result == KeyTestResult.Ok
        val color = if (ok) BestNoteTheme.colors.success else MaterialTheme.colorScheme.error
        val text = when (result) {
            null -> stringResource(R.string.settings_ai_saved)
            KeyTestResult.Ok -> stringResource(R.string.settings_ai_ok)
            KeyTestResult.NoKey -> stringResource(R.string.settings_ai_err_nokey)
            KeyTestResult.Invalid -> stringResource(R.string.settings_ai_err_invalid)
            KeyTestResult.ModelUnavailable -> stringResource(R.string.settings_ai_err_model)
            KeyTestResult.RateLimited -> stringResource(R.string.settings_ai_err_rate)
            KeyTestResult.Offline -> stringResource(R.string.settings_ai_err_offline)
            KeyTestResult.NoBaseUrl -> stringResource(R.string.settings_ai_err_base_url)
            KeyTestResult.Failed -> stringResource(R.string.settings_ai_err_failed)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(BestNoteShapes.Control)
                .background(color.copy(alpha = 0.10f))
                .padding(Spacing.M),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.S),
        ) {
            Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = color)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun AiProviderKind.label(): String = when (this) {
    AiProviderKind.Gemini -> stringResource(R.string.settings_ai_gemini)
    AiProviderKind.Claude -> stringResource(R.string.settings_ai_claude)
    AiProviderKind.OpenAi -> stringResource(R.string.settings_ai_openai)
    AiProviderKind.OpenAiCompatible -> stringResource(R.string.settings_ai_compatible)
}

@Composable
private fun AiProviderKind.subtitle(): String = when (this) {
    AiProviderKind.Gemini -> stringResource(R.string.settings_ai_gemini_sub)
    AiProviderKind.Claude -> stringResource(R.string.settings_ai_claude_sub)
    AiProviderKind.OpenAi -> stringResource(R.string.settings_ai_openai_sub)
    AiProviderKind.OpenAiCompatible -> stringResource(R.string.settings_ai_compatible_sub)
}

/**
 * Settings → Smart Board AI → Model: automatic best / fast (resolved from the provider's live list),
 * favourites and recent models on top, then every model the key can use, and a field for any id.
 */
@Composable
private fun ModelPicker(state: SmartBoardUiState, viewModel: SmartBoardViewModel) {
    val stored = state.stored
    SettingsGroup(stringResource(R.string.settings_ai_model_group)) {
        row {
            Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (stored.resolvedModel.isBlank()) stringResource(R.string.settings_ai_model_none)
                            else stringResource(R.string.settings_ai_model_current, stored.displayName(stored.resolvedModel)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            when {
                                state.loadingModels -> stringResource(R.string.settings_ai_models_loading)
                                state.modelsError != null -> stringResource(R.string.settings_ai_models_failed, resultText(state.modelsError))
                                stored.modelsFetchedAt == 0L -> stringResource(R.string.settings_ai_models_never)
                                else -> stringResource(R.string.settings_ai_models_count, stored.models.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.modelsError != null) MaterialTheme.colorScheme.error else BestNoteTheme.colors.muted,
                        )
                    }
                    if (state.loadingModels) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refreshModels, enabled = state.canListModels) {
                            Icon(Icons.Outlined.Refresh, stringResource(R.string.settings_ai_models_refresh))
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    if (!state.compatible) {
                        FilterChip(
                            selected = stored.model.isBlank(),
                            onClick = { viewModel.selectModel("") },
                            label = { Text(stringResource(R.string.settings_ai_model_auto_best, stored.displayName(state.autoBest))) },
                        )
                        FilterChip(
                            selected = stored.model == ModelCatalog.AUTO_FAST,
                            onClick = { viewModel.selectModel(ModelCatalog.AUTO_FAST) },
                            label = { Text(stringResource(R.string.settings_ai_model_auto_fast, stored.displayName(state.autoFast))) },
                        )
                    }
                    state.pinned.forEach { id ->
                        FilterChip(
                            selected = stored.model == id,
                            onClick = { viewModel.selectModel(id) },
                            label = { Text(stored.displayName(id)) },
                            leadingIcon = if (id in stored.favorites) ({ Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
                if (stored.models.size > FILTER_FROM) {
                    OutlinedTextField(
                        value = state.modelFilter,
                        onValueChange = viewModel::onFilterChange,
                        label = { Text(stringResource(R.string.settings_ai_models_filter)) },
                        singleLine = true,
                        shape = BestNoteShapes.Control,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        state.filteredModels.take(MAX_ROWS).forEach { model ->
            row {
                RadioRow(
                    title = model.displayName,
                    subtitle = model.id,
                    selected = stored.model == model.id,
                    onSelect = { viewModel.selectModel(model.id) },
                    trailing = {
                        val fav = model.id in stored.favorites
                        IconButton(onClick = { viewModel.toggleFavorite(model.id) }) {
                            Icon(
                                if (fav) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                stringResource(if (fav) R.string.settings_ai_model_unfavorite else R.string.settings_ai_model_favorite),
                                tint = if (fav) MaterialTheme.colorScheme.primary else BestNoteTheme.colors.muted,
                            )
                        }
                    },
                )
            }
        }
        row {
            Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                OutlinedTextField(
                    value = state.customModelDraft,
                    onValueChange = viewModel::onCustomModelChange,
                    label = { Text(stringResource(R.string.settings_ai_model_custom)) },
                    supportingText = {
                        Text(stringResource(if (state.compatible) R.string.settings_ai_model_required else R.string.settings_ai_model_custom_sub))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    trailingIcon = {
                        if (state.customModelDraft.isNotBlank()) TextButton(onClick = viewModel::save) { Text(stringResource(R.string.settings_ai_save)) }
                    },
                    shape = BestNoteShapes.Control,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun resultText(result: KeyTestResult?): String = when (result) {
    null, KeyTestResult.Ok -> ""
    KeyTestResult.NoKey -> stringResource(R.string.settings_ai_err_nokey)
    KeyTestResult.Invalid -> stringResource(R.string.settings_ai_err_invalid)
    KeyTestResult.ModelUnavailable -> stringResource(R.string.settings_ai_err_model)
    KeyTestResult.RateLimited -> stringResource(R.string.settings_ai_err_rate)
    KeyTestResult.Offline -> stringResource(R.string.settings_ai_err_offline)
    KeyTestResult.NoBaseUrl -> stringResource(R.string.settings_ai_err_base_url)
    KeyTestResult.Failed -> stringResource(R.string.settings_ai_err_failed)
}

private const val FILTER_FROM = 12
private const val MAX_ROWS = 60

/** Defaults for new notebooks: the design style and the language the notes are written in. */
@Composable
private fun NotesDefaults(viewModel: SmartBoardNotesViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()) {
    val notes by viewModel.ui.collectAsStateWithLifecycle()
    SettingsGroup(stringResource(R.string.settings_ai_notes_group)) {
        row {
            Column(Modifier.fillMaxWidth().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Text(stringResource(R.string.settings_ai_notes_style), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_ai_notes_style_sub), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    app.bestnote.engine.layout.StyleTemplates.all.forEach { style ->
                        FilterChip(selected = style.id == notes.style.id, onClick = { viewModel.setStyle(style) }, label = { Text(styleLabel(style.id)) })
                    }
                }
                Text(stringResource(R.string.settings_ai_notes_language), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Spacing.S))
                Text(stringResource(R.string.settings_ai_notes_language_sub), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    app.bestnote.ai.vision.NotesLanguage.entries.forEach { l ->
                        FilterChip(selected = l == notes.language, onClick = { viewModel.setLanguage(l) }, label = { Text(languageLabel(l)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun styleLabel(id: String) = stringResource(
    when (id) {
        "minimal" -> R.string.settings_ai_style_minimal
        "colorful" -> R.string.settings_ai_style_colorful
        "cornell" -> R.string.settings_ai_style_cornell
        "cheatsheet" -> R.string.settings_ai_style_cheatsheet
        "study-columns" -> R.string.settings_ai_style_study
        else -> R.string.settings_ai_style_classic
    },
)

@Composable
private fun languageLabel(l: app.bestnote.ai.vision.NotesLanguage): String = when (l) {
    app.bestnote.ai.vision.NotesLanguage.Board -> stringResource(R.string.settings_ai_language_board)
    app.bestnote.ai.vision.NotesLanguage.English -> "English"
    app.bestnote.ai.vision.NotesLanguage.Hebrew -> "עברית"
    app.bestnote.ai.vision.NotesLanguage.Arabic -> "العربية"
    app.bestnote.ai.vision.NotesLanguage.French -> "Français"
    app.bestnote.ai.vision.NotesLanguage.Spanish -> "Español"
    app.bestnote.ai.vision.NotesLanguage.German -> "Deutsch"
    app.bestnote.ai.vision.NotesLanguage.Russian -> "Русский"
    app.bestnote.ai.vision.NotesLanguage.Turkish -> "Türkçe"
}
