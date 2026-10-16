package app.bestnote.feature.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.templates.TemplateChangeItem

/**
 * "Design with AI": describe the template in words ("cheat sheet for calculus exams, Arabic with Hebrew
 * terms, blue headings, red formulas"); the provider the user picked answers with the template, the
 * preview updates, and what changed is listed under the answer. Talk again to refine; Save keeps it.
 */
@Composable
internal fun DesignChat(editor: EditorState, vm: TemplatesViewModel) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(editor.chat.size) { if (editor.chat.isNotEmpty()) list.animateScrollToItem(editor.chat.size - 1) }
    Column(Modifier.fillMaxSize()) {
        if (editor.chat.isEmpty()) {
            Column(Modifier.weight(1f).padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Text(stringResource(R.string.tpl_design_intro), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.tpl_design_example), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                items(editor.chat) { Bubble(it) }
            }
        }
        if (editor.undo.isNotEmpty()) TextButton(onClick = vm::undoAi, modifier = Modifier.padding(horizontal = Spacing.S)) { Text(stringResource(R.string.tpl_design_undo)) }
        Row(Modifier.fillMaxWidth().padding(Spacing.M), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it.take(600) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "design-input" },
                placeholder = { Text(stringResource(R.string.tpl_design_hint)) },
                maxLines = 4,
            )
            if (editor.busy) CircularProgressIndicator(Modifier.padding(Spacing.M).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { vm.design(input); input = "" }, enabled = input.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.tpl_design_send)) }
        }
    }
}

@Composable
private fun Bubble(e: ChatEntry) {
    Surface(
        shape = BestNoteShapes.Card,
        color = if (e.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) {
            if (e.failure != null) {
                Text(
                    stringResource(
                        when (e.failure) {
                            ChatFailure.NoKey -> R.string.tpl_design_err_key
                            ChatFailure.Network -> R.string.tpl_design_err_network
                            ChatFailure.Limit -> R.string.tpl_design_err_limit
                            ChatFailure.Blocked -> R.string.tpl_design_err_blocked
                            ChatFailure.Unusable -> R.string.tpl_design_err_unusable
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                if (e.text.isNotBlank()) Text(e.text)
                if (!e.fromUser) {
                    if (e.changes.isEmpty()) Text(stringResource(R.string.tpl_design_nochange), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
                    else {
                        Text(stringResource(R.string.tpl_design_changed), style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted)
                        e.changes.take(MAX_SHOWN).forEach { Text("• " + describe(it), style = MaterialTheme.typography.bodySmall) }
                        if (e.changes.size > MAX_SHOWN) Text(stringResource(R.string.tpl_design_more, e.changes.size - MAX_SHOWN), style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.adjusted > 0) Text(stringResource(R.string.tpl_design_adjusted, e.adjusted), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.warning)
                }
            }
        }
    }
}

private const val MAX_SHOWN = 12

/** "Headings colour: #2A4BD7 → #0000FF". */
@Composable
private fun describe(c: TemplateChangeItem): String {
    val arrow = "${c.from} → ${c.to}"
    val role = c.key.substringAfter('.', "").let { r -> NoteRole.entries.firstOrNull { it.name == r } }
    return when {
        c.key.startsWith("color.") && role != null -> stringResource(R.string.tpl_chg_color, roleName(role), arrow)
        c.key.startsWith("scale.") && role != null -> stringResource(R.string.tpl_chg_size, roleName(role), arrow)
        c.key.startsWith("decoration.") && role != null -> stringResource(R.string.tpl_chg_deco, roleName(role), arrow)
        c.key == "font.latin" -> stringResource(R.string.tpl_chg_font, stringResource(R.string.tpl_font_latin), arrow)
        c.key == "font.arabic" -> stringResource(R.string.tpl_chg_font, stringResource(R.string.tpl_font_arabic), arrow)
        c.key == "font.hebrew" -> stringResource(R.string.tpl_chg_font, stringResource(R.string.tpl_font_hebrew), arrow)
        c.key == "name" -> stringResource(R.string.tpl_chg_name, c.to)
        c.key == "bodyScale" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_look_body_size), arrow)
        c.key == "spaceBeforeHeading" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_look_space_before), arrow)
        c.key == "layout" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_look_layout), arrow)
        c.key == "density" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_look_density), arrow)
        c.key == "marginArrows" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_look_margin_arrows), arrow)
        c.key == "paper" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_look_paper), arrow)
        c.key == "highlight" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_deco_highlight), arrow)
        c.key == "ai.notesLanguage" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_ai_notes_language), arrow)
        c.key == "ai.termsLanguage" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_ai_terms_language), arrow)
        c.key == "ai.termsStyle" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_ai_terms_style), arrow)
        c.key == "ai.detail" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_ai_detail), arrow)
        c.key == "ai.stepByStep" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_ai_step_by_step), arrow)
        c.key == "ai.glossary" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_ai_glossary), arrow)
        c.key == "ai.note" -> stringResource(R.string.tpl_chg_generic, stringResource(R.string.tpl_ai_note), "")
        c.key == "elements.added" -> stringResource(R.string.tpl_chg_added, c.to)
        c.key == "elements.removed" -> stringResource(R.string.tpl_chg_removed, c.to)
        else -> c.key
    }
}
