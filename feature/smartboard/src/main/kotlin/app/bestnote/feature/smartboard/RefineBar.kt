package app.bestnote.feature.smartboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bestnote.ai.vision.FontSlot
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.fonts.FontPickerDialog
import app.bestnote.core.fonts.FontScript

/**
 * AI-13 "Ask Smart Board…", docked under the result: type (or dictate with the keyboard's mic) what to
 * change. Template, font, colour and undo are handled on the device; anything else is an AI edit of
 * the notes. Recent requests are one tap away; each change is one undo step.
 */
@Composable
internal fun RefineBar(state: SmartBoardUiState, viewModel: SmartBoardViewModel, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var pickFont by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val refine = state.refine
    val send = {
        if (text.isNotBlank()) {
            viewModel.refine(text)
            text = ""
            focus.clearFocus()
        }
    }
    Surface(modifier.fillMaxWidth(), tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { pickFont = true },
                    label = { Text(stringResource(R.string.smartboard_refine_font)) },
                    leadingIcon = { Icon(Icons.Rounded.TextFields, null, Modifier.size(18.dp)) },
                )
                if (state.storedPhotos > 0 || state.photos.isNotEmpty()) {
                    AssistChip(
                        onClick = viewModel::viewPhotos,
                        label = { Text(stringResource(R.string.smartboard_photos_view)) },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)) },
                    )
                }
                refine.requests.forEach { r ->
                    AssistChip(onClick = { text = r }, label = { Text(r, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) })
                }
            }
            refine.error?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            when (e) {
                                RefineError.NeedsKey -> R.string.smartboard_refine_needs_key
                                RefineError.NotUnderstood -> R.string.smartboard_refine_not_understood
                                RefineError.Network -> R.string.smartboard_error_network
                                RefineError.RateLimited -> R.string.smartboard_error_rate
                                RefineError.Other -> R.string.smartboard_refine_failed
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = BestNoteTheme.colors.warning,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = viewModel::dismissRefineError) { Icon(Icons.Rounded.Close, stringResource(R.string.smartboard_refine_dismiss)) }
                }
            }
            if (refine.skipped > 0 && refine.error == null) {
                Text(stringResource(R.string.smartboard_refine_partial, refine.skipped), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                IconButton(onClick = viewModel::undoRefine, enabled = refine.canUndo && !refine.busy) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.smartboard_refine_undo))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !refine.busy,
                    placeholder = { Text(stringResource(R.string.smartboard_refine_hint), maxLines = 1) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f),
                )
                if (refine.busy) {
                    CircularProgressIndicator(Modifier.size(28.dp).padding(Spacing.XXS), strokeWidth = 3.dp)
                } else {
                    IconButton(onClick = send, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.smartboard_refine_send)) }
                }
            }
        }
    }
    if (pickFont) {
        val slot = viewModel.fontSlot()
        FontPickerDialog(
            script = when (slot) {
                FontSlot.Latin -> FontScript.Latin
                FontSlot.Arabic -> FontScript.Arabic
                FontSlot.Hebrew -> FontScript.Hebrew
            },
            selectedId = viewModel.currentFont(slot),
            onSelect = { id -> viewModel.setFont(id, slot); pickFont = false },
            onDismiss = { pickFont = false },
        )
    }
}
