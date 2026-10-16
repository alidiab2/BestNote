package app.bestnote.feature.editor.audio

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.AudioRecording
import app.bestnote.feature.editor.AtelierIcons
import app.bestnote.feature.editor.ChromeIconButton
import app.bestnote.feature.editor.R
import app.bestnote.feature.editor.chromeSurface
import java.util.Date
import java.util.Locale

/** Line icons for the audio chrome, in the same 24 px / 1.8 stroke language as `AtelierIcons`. */
internal object AudioIcons {
    private fun icon(name: String, vararg paths: String, filled: List<String> = emptyList(), mirror: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {
            paths.forEach {
                addPath(addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
            filled.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Play = icon("play", filled = listOf("M8 5.5v13l11-6.5z"), mirror = true)
    val Pause = icon("pause", filled = listOf("M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"))
    val Stop = icon("stop", filled = listOf("M7 7h10v10H7z"))
    val Close = icon("close", "M6.5 6.5l11 11M17.5 6.5l-11 11")
    val List = icon("list", "M9 7h10M9 12h10M9 17h10", "M5 7h.01M5 12h.01M5 17h.01")
    val Rename = icon("rename", "M4 20h4l10.5-10.5a2.1 2.1 0 0 0 -3 -3L5 17z", "M13.5 8l3 3")
    val Ink = icon("ink", "M4 20l1.2-4.2L16.5 4.5a2.1 2.1 0 0 1 3 3L8.2 18.8z", "M14.5 6.5l3 3")
}

/** `m:ss` (or `h:mm:ss`) for a position in ms. */
internal fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

@Composable
internal fun displayName(r: AudioRecording): String {
    r.title?.let { return it }
    val time = DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current).format(Date(r.startedAtMillis))
    return stringResource(R.string.audio_default_name, time)
}

/**
 * The small floating pill while recording (timer + stop) or playing (play/pause, position, "play from
 * ink"). Static while the pen is down: it only changes text once a second and never animates.
 */
@Composable
internal fun AudioPill(
    state: AudioState,
    audio: AudioController,
    onPlayFromInk: (Boolean) -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rec = state.recording
    val current = state.recordings.firstOrNull { it.id == state.current }
    if (rec == null && current == null) return
    Row(
        modifier
            .chromeSurface(BestNoteShapes.Pill, alpha = 0.94f)
            .padding(horizontal = Spacing.S, vertical = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
    ) {
        if (rec != null) {
            var elapsed by remember(rec.recordingId) { mutableLongStateOf(0L) }
            LaunchedEffect(rec.recordingId) {
                while (true) {
                    elapsed = SystemClock.uptimeMillis() - rec.startUptime
                    kotlinx.coroutines.delay(RecordingTickMs)
                }
            }
            val label = stringResource(R.string.audio_recording_now, clock(elapsed))
            Row(Modifier.padding(start = Spacing.S).semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                Text(
                    clock(elapsed),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = Spacing.S, end = Spacing.XS).widthIn(min = 40.dp),
                )
            }
            ChromeIconButton(AudioIcons.List, stringResource(R.string.audio_list), onOpenList)
            ChromeIconButton(
                AudioIcons.Stop,
                stringResource(R.string.audio_stop),
                audio::stopRecording,
                tint = MaterialTheme.colorScheme.error,
            )
        } else if (current != null) {
            ChromeIconButton(
                if (state.playing) AudioIcons.Pause else AudioIcons.Play,
                stringResource(if (state.playing) R.string.audio_pause else R.string.audio_play),
                { audio.togglePlay(current.id) },
            )
            Text(
                clock(state.positionMs) + " / " + clock(current.durationMillis),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.XS),
            )
            val inkShape = CircleShape
            ChromeIconButton(
                AudioIcons.Ink,
                stringResource(R.string.audio_play_from_ink),
                { onPlayFromInk(!state.playFromInk) },
                shape = inkShape,
                tint = if (state.playFromInk) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .then(if (state.playFromInk) Modifier.background(MaterialTheme.colorScheme.primaryContainer, inkShape) else Modifier)
                    .semantics { this.selected = state.playFromInk },
            )
            ChromeIconButton(AudioIcons.List, stringResource(R.string.audio_list), onOpenList)
            ChromeIconButton(AudioIcons.Close, stringResource(R.string.audio_close), audio::closePlayer)
        }
    }
}

private const val RecordingTickMs = 500L

/** The recordings of this notebook: play/pause, seek, rename, delete, and the "play from ink" switch. */
@Composable
internal fun RecordingsSheet(
    state: AudioState,
    audio: AudioController,
    onPlayFromInk: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<AudioRecording?>(null) }
    var deleting by remember { mutableStateOf<AudioRecording?>(null) }
    // A scrim that closes the sheet; the panel itself swallows taps.
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = ScrimAlpha)).clickable(onClick = onDismiss)) {
        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(Spacing.L)
                .widthIn(max = SheetMaxWidth)
                .heightIn(max = SheetMaxHeight)
                .softShadow(Elevation.Medium, BestNoteShapes.Sheet)
                .clickable(enabled = false, onClick = {}),
            shape = BestNoteShapes.Sheet,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(vertical = Spacing.L)) {
                Row(Modifier.padding(horizontal = Spacing.L), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.audio_recordings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    ChromeIconButton(AudioIcons.Close, stringResource(R.string.audio_close), onDismiss)
                }
                Row(Modifier.padding(horizontal = Spacing.L, vertical = Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.audio_play_from_ink), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.audio_play_from_ink_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(state.playFromInk, onPlayFromInk, modifier = Modifier.padding(start = Spacing.M))
                }
                if (state.recordings.isEmpty()) {
                    Text(
                        stringResource(R.string.audio_recordings_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.L),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(state.recordings, key = { it.id }) { r ->
                            RecordingRow(r, state, audio, onRename = { renaming = r }, onDelete = { deleting = r })
                        }
                    }
                }
            }
        }
    }
    renaming?.let { r -> RenameDialog(r, onDismiss = { renaming = null }) { audio.rename(r.id, it); renaming = null } }
    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.audio_delete_title)) },
            text = { Text(stringResource(R.string.audio_delete_text)) },
            confirmButton = {
                TextButton({ audio.delete(r.id); deleting = null }) { Text(stringResource(R.string.audio_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.audio_cancel)) } },
        )
    }
}

@Composable
private fun RecordingRow(
    r: AudioRecording,
    state: AudioState,
    audio: AudioController,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCurrent = state.current == r.id
    val playing = isCurrent && state.playing
    val date = DateFormat.getMediumDateFormat(androidx.compose.ui.platform.LocalContext.current).format(Date(r.startedAtMillis))
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.S)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChromeIconButton(
                if (playing) AudioIcons.Pause else AudioIcons.Play,
                stringResource(if (playing) R.string.audio_pause else R.string.audio_play),
                { audio.togglePlay(r.id) },
                size = Spacing.TouchTarget,
            )
            Column(Modifier.weight(1f).padding(horizontal = Spacing.XS)) {
                Text(displayName(r), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    date + " · " + clock(r.durationMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChromeIconButton(AudioIcons.Rename, stringResource(R.string.audio_rename), onRename, size = Spacing.TouchTarget)
            ChromeIconButton(AtelierIcons.Trash, stringResource(R.string.audio_delete), onDelete, size = Spacing.TouchTarget)
        }
        if (isCurrent) {
            val seekLabel = stringResource(R.string.audio_seek)
            Slider(
                value = state.positionMs.toFloat(),
                onValueChange = { audio.seekTo(it.toLong()) },
                valueRange = 0f..r.durationMillis.coerceAtLeast(1).toFloat(),
                modifier = Modifier.padding(horizontal = Spacing.M).semantics { contentDescription = seekLabel },
            )
        }
    }
}

@Composable
private fun RenameDialog(r: AudioRecording, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(r.id) { mutableStateOf(r.title.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_rename_title)) },
        text = {
            OutlinedTextField(
                text,
                { text = it },
                singleLine = true,
                placeholder = { Text(displayName(r)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton({ onSave(text) }) { Text(stringResource(R.string.audio_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.audio_cancel)) } },
    )
}

private const val ScrimAlpha = 0.25f
private val SheetMaxWidth = 520.dp
private val SheetMaxHeight = 560.dp

