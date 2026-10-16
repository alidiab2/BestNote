package app.bestnote.core.updater

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing

/**
 * The update prompt, hosted once at the top of the app. Shown when a newer build is found
 * (respecting Later/Skip) and while an update downloads.
 */
@Composable
fun UpdateHost(manager: UpdateManager) {
    val prompt by manager.prompt.collectAsStateWithLifecycle()
    val status by manager.status.collectAsStateWithLifecycle()
    var hidden by remember { mutableStateOf<Long?>(null) }

    val release = when (val s = status) {
        is UpdateStatus.Downloading -> s.release
        is UpdateStatus.NeedsPermission -> s.release
        is UpdateStatus.Failed -> s.release
        else -> prompt
    } ?: return
    if (hidden == release.versionCode && status is UpdateStatus.Downloading) return

    UpdateDialog(
        release = release,
        current = manager.build,
        status = status,
        onUpdate = { hidden = null; manager.update(release) },
        onLater = manager::later,
        onSkip = { manager.skip(release) },
        onHide = { hidden = release.versionCode },
        onClose = manager::dismissPrompt,
    )
}

@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    current: BuildInfo,
    status: UpdateStatus,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val busy = status is UpdateStatus.Downloading
    AlertDialog(
        onDismissRequest = { if (busy) onHide() else if (status is UpdateStatus.Available || status is UpdateStatus.Idle) onLater() else onClose() },
        shape = BestNoteShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text(stringResource(R.string.update_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.update_versions, current.versionName, release.versionName) +
                        if (release.prerelease) " · " + stringResource(R.string.update_prerelease) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = BestNoteTheme.colors.muted,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                when (status) {
                    is UpdateStatus.Downloading -> {
                        val fraction = if (status.total > 0) status.done.toFloat() / status.total else 0f
                        Text(
                            stringResource(
                                R.string.update_downloading,
                                Formatter.formatShortFileSize(context, status.done),
                                Formatter.formatShortFileSize(context, status.total),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateStatus.NeedsPermission ->
                        Text(stringResource(R.string.update_needs_permission), style = MaterialTheme.typography.bodyMedium)
                    is UpdateStatus.Failed ->
                        Text(stringResource(R.string.update_failed, status.message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    else -> {}
                }
                if (release.notes.isNotBlank()) {
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(MarkdownLite.render(release.notes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate, enabled = !busy) { Text(stringResource(if (status is UpdateStatus.Failed) R.string.update_retry else R.string.update_now)) }
        },
        dismissButton = {
            if (busy) {
                TextButton(onClick = onHide) { Text(stringResource(R.string.update_hide)) }
            } else {
                Row(Modifier.padding(end = Spacing.S), horizontalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                    TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                    TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                }
            }
        },
    )
}

/** Release notes are GitHub markdown; headings, bullets and **bold** are enough here. */
object MarkdownLite {
    fun render(text: String) = buildAnnotatedString {
        text.lines().forEachIndexed { i, raw ->
            if (i > 0) append('\n')
            val line = raw.trimEnd()
            val heading = Regex("""^#{1,6}\s+(.*)""").find(line)
            val bullet = Regex("""^\s*[-*+]\s+(.*)""").find(line)
            when {
                heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(heading.groupValues[1]) }
                bullet != null -> { append("• "); appendInline(bullet.groupValues[1]) }
                else -> appendInline(line)
            }
        }
    }

    private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(s: String) {
        val bold = Regex("""\*\*(.+?)\*\*""")
        var last = 0
        for (m in bold.findAll(s)) {
            append(s.substring(last, m.range.first).replace("`", ""))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1].replace("`", "")) }
            last = m.range.last + 1
        }
        append(s.substring(last).replace("`", ""))
    }
}
