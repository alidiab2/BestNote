package app.bestnote.feature.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.feature.settings.DebugEvent
import app.bestnote.feature.settings.DebugViewModel
import app.bestnote.feature.settings.R

/** Settings → Debug: share / copy / view a redacted debug log, and opt into raw AI response capture. */
@Composable
internal fun DebugSection(viewModel: DebugViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.settings_debug_share)
    val copied = stringResource(R.string.settings_debug_copied)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is DebugEvent.Share -> {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(context.contentResolver.getType(event.uri) ?: "text/plain")
                        .putExtra(Intent.EXTRA_STREAM, event.uri)
                        .putExtra(Intent.EXTRA_SUBJECT, "BestNote debug log")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, shareTitle))
                }
                is DebugEvent.Copy -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("BestNote debug log", event.text))
                    android.widget.Toast.makeText(context, copied, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    DetailHeader(stringResource(R.string.settings_cat_debug), stringResource(R.string.settings_debug_desc))

    SettingsGroup(stringResource(R.string.settings_debug_log_group)) {
        row {
            SettingsRow(
                title = shareTitle,
                subtitle = stringResource(R.string.settings_debug_share_sub),
                icon = Icons.Outlined.Share,
                enabled = !state.busy,
                modifier = Modifier.clickable(enabled = !state.busy, role = Role.Button, onClick = viewModel::share),
            ) { if (state.busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.heightIn(max = 20.dp)) }
        }
        row {
            SettingsRow(
                title = stringResource(R.string.settings_debug_copy),
                icon = Icons.Outlined.ContentCopy,
                enabled = !state.busy,
                modifier = Modifier.clickable(enabled = !state.busy, role = Role.Button, onClick = viewModel::copy),
            )
        }
        row {
            SettingsRow(
                title = stringResource(R.string.settings_debug_view),
                icon = Icons.Outlined.Description,
                enabled = !state.busy,
                modifier = Modifier.clickable(enabled = !state.busy, role = Role.Button, onClick = viewModel::view),
            )
        }
    }

    SettingsGroup(stringResource(R.string.settings_debug_ai_group)) {
        row {
            SwitchRow(
                title = stringResource(R.string.settings_debug_verbose),
                subtitle = stringResource(R.string.settings_debug_verbose_sub),
                icon = Icons.Outlined.Terminal,
                checked = state.verboseAi,
                onCheckedChange = viewModel::setVerboseAi,
            )
        }
    }

    state.viewing?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::closeViewer,
            title = { Text(stringResource(R.string.settings_debug_view)) },
            text = {
                SelectionContainer {
                    Text(
                        text,
                        // Logs are left-to-right even in Arabic UI.
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, textDirection = TextDirection.Ltr),
                        modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = viewModel::share) { Text(shareTitle) } },
            dismissButton = {
                TextButton(onClick = viewModel::copy) { Text(stringResource(R.string.settings_debug_copy)) }
                TextButton(onClick = viewModel::closeViewer) { Text(stringResource(R.string.settings_debug_close)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
