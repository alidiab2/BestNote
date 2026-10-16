package app.bestnote.feature.templates

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.StyleTemplates
import app.bestnote.engine.templates.TemplateExchange
import kotlinx.serialization.Serializable

@Serializable
data object TemplatesRoute

fun NavController.navigateToTemplates() = navigate(TemplatesRoute)

fun NavGraphBuilder.templatesScreen(onBack: () -> Unit) {
    composable<TemplatesRoute> { TemplatesScreen(onBack) }
}

/** Localised name of a built-in template; user templates are named by their owner. */
@Composable
fun templateDisplayName(t: NoteTemplate): String = if (!t.builtIn) t.name else stringResource(
    when (t.id) {
        StyleTemplates.Minimal.id -> R.string.tpl_style_minimal
        StyleTemplates.Colorful.id -> R.string.tpl_style_colorful
        StyleTemplates.Cornell.id -> R.string.tpl_style_cornell
        StyleTemplates.CheatSheet.id -> R.string.tpl_style_cheatsheet
        NoteTemplates.STUDY_COLUMNS -> R.string.tpl_style_study
        else -> R.string.tpl_style_classic
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(onBack: () -> Unit, vm: TemplatesViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val editor = ui.editor
    var confirmDiscard by remember { mutableStateOf(false) }
    val back = { if (editor != null) { if (editor.dirty) confirmDiscard = true else vm.close() } else if (ui.viewing != null) vm.closeViewing() else onBack() }
    BackHandler(onBack = back)
    val message = ui.message
    val text = message?.let {
        stringResource(
            when (it) {
                TemplatesMessage.Imported -> R.string.tpl_msg_imported
                TemplatesMessage.ImportNotATemplate -> R.string.tpl_msg_import_bad
                TemplatesMessage.ImportTooLarge -> R.string.tpl_msg_import_large
                TemplatesMessage.ImportTooNew -> R.string.tpl_msg_import_new
                TemplatesMessage.Exported -> R.string.tpl_msg_exported
                TemplatesMessage.Saved -> R.string.tpl_msg_saved
            },
        )
    }
    LaunchedEffect(message) {
        if (text != null) { snack.showSnackbar(text); vm.consumeMessage() }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(editor?.let { stringResource(R.string.tpl_editor_title) } ?: stringResource(R.string.tpl_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.tpl_back)) } },
                actions = {
                    if (editor == null) IconButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
                        Icon(Icons.Rounded.FileUpload, stringResource(R.string.tpl_import))
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                editor != null -> TemplateEditor(editor, vm)
                else -> TemplateList(ui, vm)
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.tpl_discard_title)) },
            text = { Text(stringResource(R.string.tpl_discard_body)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.save(); vm.close() }) { Text(stringResource(R.string.tpl_save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.tpl_cancel)) }
                    TextButton(onClick = { confirmDiscard = false; vm.close() }) { Text(stringResource(R.string.tpl_discard)) }
                }
            },
        )
    }
}

@Composable
private fun TemplateList(ui: TemplatesUiState, vm: TemplatesViewModel) {
    var newName by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<NoteTemplate?>(null) }
    var deleting by remember { mutableStateOf<NoteTemplate?>(null) }
    var exporting by remember { mutableStateOf<NoteTemplate?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TemplateExchange.MIME)) { uri ->
        val t = exporting
        exporting = null
        if (uri != null && t != null) {
            vm.exportText(t.id)?.let { text -> vm.writeTo(uri, text) }
        }
    }
    val viewing = ui.viewing
    if (viewing != null) {
        BuiltInView(viewing, onDuplicate = { vm.duplicate(viewing.id) }, onClose = vm::closeViewing)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        item {
            Text(stringResource(R.string.tpl_intro), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
            Spacer(Modifier.size(Spacing.S))
            TextButton(onClick = { newName = "" }) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(Spacing.XS)); Text(stringResource(R.string.tpl_new))
            }
        }
        items(ui.templates, key = { it.id }) { t ->
            var menu by remember { mutableStateOf(false) }
            Surface(
                shape = BestNoteShapes.Card,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().clickable { vm.edit(t.id) }.semantics { contentDescription = "template-${t.id}" },
            ) {
                Row(Modifier.padding(Spacing.M), verticalAlignment = Alignment.CenterVertically) {
                    ColorDots(t)
                    Spacer(Modifier.width(Spacing.M))
                    Column(Modifier.weight(1f)) {
                        Text(templateDisplayName(t), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(if (t.builtIn) R.string.tpl_builtin else R.string.tpl_mine) + " · " + stringResource(R.string.tpl_element_count, t.elements.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = BestNoteTheme.colors.muted,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.tpl_more)) }
                        DropdownMenu(menu, { menu = false }) {
                            if (!t.builtIn) DropdownMenuItem({ Text(stringResource(R.string.tpl_edit)) }, onClick = { menu = false; vm.edit(t.id) })
                            DropdownMenuItem({ Text(stringResource(R.string.tpl_duplicate)) }, onClick = { menu = false; vm.duplicate(t.id, thenEdit = false) })
                            if (!t.builtIn) DropdownMenuItem({ Text(stringResource(R.string.tpl_rename)) }, onClick = { menu = false; renaming = t })
                            DropdownMenuItem({ Text(stringResource(R.string.tpl_export)) }, onClick = { menu = false; exporting = t; exporter.launch(vm.fileName(t.id)) })
                            if (!t.builtIn) DropdownMenuItem({ Text(stringResource(R.string.tpl_delete)) }, onClick = { menu = false; deleting = t })
                        }
                    }
                }
            }
        }
    }
    newName?.let { n ->
        NameDialog(R.string.tpl_new, n, onDismiss = { newName = null }) { vm.create(it); newName = null }
    }
    renaming?.let { t ->
        NameDialog(R.string.tpl_rename, t.name, onDismiss = { renaming = null }) { vm.rename(t.id, it); renaming = null }
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.tpl_delete_title, t.name)) },
            text = { Text(stringResource(R.string.tpl_delete_body)) },
            confirmButton = { TextButton(onClick = { vm.delete(t.id); deleting = null }) { Text(stringResource(R.string.tpl_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.tpl_cancel)) } },
        )
    }
}

@Composable
internal fun ColorDots(t: NoteTemplate) {
    val pen = MaterialTheme.colorScheme.onSurface
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(NoteRole.Title, NoteRole.Definition, NoteRole.Result).forEach { r ->
            Box(Modifier.size(12.dp).background(t.look.role(r).color?.let { Color(it) } ?: pen, CircleShape))
        }
    }
}

@Composable
private fun NameDialog(title: Int, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.tpl_name)) }) },
        confirmButton = { TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.tpl_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tpl_cancel)) } },
    )
}

/** A built-in template, read-only: its preview and what it asks the AI, with "Duplicate to edit". */
@Composable
private fun BuiltInView(t: NoteTemplate, onDuplicate: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Text(templateDisplayName(t), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.tpl_builtin_hint), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            androidx.compose.material3.Button(onClick = onDuplicate) { Text(stringResource(R.string.tpl_duplicate_to_edit)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.tpl_back)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            TemplatePreview(t, Modifier.fillMaxWidth(0.6f))
        }
    }
}
