package app.bestnote.engine.export

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Export dialog (DOC-06, DOC-07): pick PDF, PNG pages or a `.bnote` file, then the Android share
 * sheet opens with the result (it also offers "Save to Files"). Work runs off the main thread with a
 * progress bar and a Cancel button.
 *
 * @param currentPage zero-based page being viewed, when opened from the editor; adds "This page".
 * @param beforeExport runs first (editor: flush autosave so the file holds what is on screen).
 * @param fileName notebook title, the suggested name in the Save to Files picker.
 * @param startWith when set, starts exporting in that format immediately (the library's "Share").
 */
@Composable
fun ExportDialog(
    runner: ExportRunner,
    notebookId: NotebookId,
    pageCount: Int,
    onDismiss: () -> Unit,
    currentPage: Int? = null,
    startWith: ExportFormat? = null,
    fileName: String = "Notebook",
    beforeExport: suspend () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(startWith ?: ExportFormat.Pdf) }
    var scopeChoice by remember { mutableStateOf(if (currentPage != null && pageCount > 1) ScopeChoice.This else ScopeChoice.All) }
    var rangeText by remember { mutableStateOf("") }
    var includeTemplate by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val rangeParse = remember(rangeText, pageCount) { ExportPlan.parseRange(rangeText, pageCount) }
    val rangeInvalid = scopeChoice == ScopeChoice.Range && rangeParse !is RangeParse.Ok
    val pagesApply = format != ExportFormat.Bnote && pageCount > 1

    fun start(saveTo: Uri?) {
        error = null
        working = true
        done = 0
        total = 0
        job = scope.launch {
            try {
                beforeExport()
                val range = when {
                    !pagesApply -> PageRange.All
                    scopeChoice == ScopeChoice.This && currentPage != null -> PageRange.Single(currentPage)
                    scopeChoice == ScopeChoice.Range -> PageRange.Pages((rangeParse as RangeParse.Ok).indices)
                    else -> PageRange.All
                }
                val result = runner.export(notebookId, format, range, ExportOptions(includeTemplate)) { d, t -> done = d; total = t }
                if (saveTo != null) {
                    runner.saveTo(result, saveTo)
                    Toast.makeText(context, R.string.export_saved, Toast.LENGTH_SHORT).show()
                    onDismiss()
                } else {
                    try {
                        context.startActivity(runner.shareIntent(result))
                        onDismiss()
                    } catch (e: ActivityNotFoundException) {
                        error = e.message
                        working = false
                    }
                }
            } catch (e: CancellationException) {
                working = false
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                working = false
            }
        }
    }
    // Save to Files: the system picker first, then the export writes straight into the chosen document.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri -> if (uri != null) start(uri) }
    LaunchedEffect(Unit) { if (startWith != null) start(null) }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        shape = BestNoteShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                if (working) {
                    WorkingContent(done, total, stringResource(R.string.export_working, done.coerceAtLeast(1), total.coerceAtLeast(1)))
                } else {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
                        FormatRow(ExportFormat.Pdf, format, R.string.export_pdf, R.string.export_pdf_sub) { format = it }
                        FormatRow(ExportFormat.Png, format, R.string.export_png, R.string.export_png_sub) { format = it }
                        FormatRow(ExportFormat.Bnote, format, R.string.export_bnote, R.string.export_bnote_sub) { format = it }
                    }
                    if (pagesApply) {
                        Text(stringResource(R.string.export_pages_label), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
                        Row(Modifier.selectableGroup().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                            ScopeChip(stringResource(R.string.export_all_pages, pageCount), scopeChoice == ScopeChoice.All) { scopeChoice = ScopeChoice.All }
                            if (currentPage != null) ScopeChip(stringResource(R.string.export_this_page), scopeChoice == ScopeChoice.This) { scopeChoice = ScopeChoice.This }
                            ScopeChip(stringResource(R.string.export_range), scopeChoice == ScopeChoice.Range) { scopeChoice = ScopeChoice.Range }
                        }
                        if (scopeChoice == ScopeChoice.Range) {
                            OutlinedTextField(
                                value = rangeText,
                                onValueChange = { rangeText = it },
                                singleLine = true,
                                isError = rangeText.isNotEmpty() && rangeInvalid,
                                label = { Text(stringResource(R.string.export_range_hint, pageCount)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (format != ExportFormat.Bnote) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(includeTemplate, role = Role.Switch) { includeTemplate = it },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.export_template), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                            Switch(checked = includeTemplate, onCheckedChange = null)
                        }
                    }
                    error?.let { Text(stringResource(R.string.export_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            if (!working) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    if (format != ExportFormat.Png) {
                        val name = ExportNames.fileBase(fileName) + "." + format.extension
                        ExportPill(
                            stringResource(R.string.export_save_files),
                            { picker.launch(name) },
                            enabled = !rangeInvalid,
                            background = MaterialTheme.colorScheme.primaryContainer,
                            foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    ExportPill(stringResource(R.string.export_share), { start(null) }, enabled = !rangeInvalid)
                }
            }
        },
        dismissButton = {
            ExportPill(
                stringResource(R.string.export_cancel),
                { job?.cancel(); onDismiss() },
                background = Color.Transparent,
                foreground = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private enum class ScopeChoice { All, This, Range }

@Composable
internal fun WorkingContent(done: Int, total: Int, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (total > 0) {
            LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FormatRow(value: ExportFormat, selected: ExportFormat, title: Int, subtitle: Int, onSelect: (ExportFormat) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BestNoteShapes.Control)
            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(vertical = Spacing.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null, modifier = Modifier.padding(horizontal = Spacing.S))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(subtitle), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(BestNoteShapes.Pill)
            .background(bg)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.L, vertical = Spacing.S),
    )
}

/** Atelier pill button used by the export and backup dialogs. */
@Composable
internal fun ExportPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    background: Color = BestNoteTheme.colors.strongButton,
    foreground: Color = BestNoteTheme.colors.onStrongButton,
) {
    Row(
        modifier
            .height(44.dp)
            .clip(BestNoteShapes.Pill)
            .background(if (enabled) background else background.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.XL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = foreground)
    }
}
