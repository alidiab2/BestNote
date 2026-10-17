package app.bestnote.feature.editor.math

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.model.EquationElement
import app.bestnote.engine.mathtype.LatexPalette
import app.bestnote.feature.editor.R
import kotlin.math.roundToInt

/** The convert dialogs and the editor sheet of [EquationTools]. [onOpenSettings] opens Settings (to add an AI provider). */
@Composable
internal fun EquationTools.EquationDialogs(onOpenSettings: () -> Unit) {
    ConvertDialogs(onOpenSettings)
    if (editing != null) EquationEditorSheet()
}

@Composable
private fun EquationTools.ConvertDialogs(onOpenSettings: () -> Unit) {
    val title = stringResource(R.string.equation_title)
    when (step) {
        EquationTools.Step.Idle -> Unit
        EquationTools.Step.NoProvider -> AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(title) },
            text = { Text(stringResource(R.string.equation_no_provider)) },
            confirmButton = {
                TextButton({
                    dismiss()
                    onOpenSettings()
                }) { Text(stringResource(R.string.equation_open_settings)) }
            },
            dismissButton = {
                Row {
                    TextButton(::typeInstead) { Text(stringResource(R.string.equation_type_instead)) }
                    TextButton(::dismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            },
        )
        EquationTools.Step.Reading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(stringResource(R.string.equation_reading))
                }
            },
            confirmButton = {},
        )
        EquationTools.Step.Confirm -> AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    PreviewCard(draft.text, Modifier.fillMaxWidth().height(140.dp))
                    problem?.let { Text(stringResource(R.string.equation_ai_problem, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    LatexField(draft, { draft = it }, stringResource(R.string.equation_latex), Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.equation_keep_ink), Modifier.weight(1f))
                        Switch(checked = keepInk, onCheckedChange = { keepInk = it })
                    }
                }
            },
            confirmButton = { TextButton(::insert, enabled = draft.text.isNotBlank()) { Text(stringResource(R.string.convert_insert)) } },
            dismissButton = { TextButton(::dismiss) { Text(stringResource(R.string.dialog_cancel)) } },
        )
    }
}

/** The formula on a paper-like card, so the preview looks the way it will on the page. */
@Composable
private fun PreviewCard(latex: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, tonalElevation = 1.dp) {
        if (latex.isBlank()) {
            Text(stringResource(R.string.equation_empty_preview), Modifier.padding(Spacing.L), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            EquationPreview(latex, Modifier.padding(Spacing.M), maxSizeDp = 44.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EquationTools.EquationEditorSheet() {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var group by rememberSaveable { mutableIntStateOf(0) }
    val penPx = with(LocalDensity.current) { PEN_DP.dp.toPx() }
    ModalBottomSheet(onDismissRequest = ::closeEditor, sheetState = sheet, sheetGesturesEnabled = !rewriting) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.XL)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.equation_edit_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val copy = stringResource(R.string.equation_copy_latex)
                IconButton({ copyLatex(editText.text) }, Modifier.semantics { contentDescription = copy }) { Icon(Icons.Rounded.ContentCopy, null) }
                TextButton(::closeEditor) { Text(stringResource(R.string.dialog_cancel)) }
                FilledTonalButton(::applyEdit, enabled = editText.text.isNotBlank()) { Text(stringResource(R.string.equation_done)) }
            }
            PreviewCard(editText.text, Modifier.fillMaxWidth().height(180.dp))
            LatexField(editText, { editText = it }, stringResource(R.string.equation_latex), Modifier.fillMaxWidth())

            // Structure palette: a row of groups, then the group's buttons, each showing what it inserts.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                LatexPalette.groups.forEachIndexed { k, _ ->
                    FilterChip(selected = group == k, onClick = { group = k }, label = { Text(stringResource(GROUP_NAMES.getOrElse(k) { R.string.equation_group_structures })) })
                }
                TextButton(::nextSlot) { Text(stringResource(R.string.equation_next_slot)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                LatexPalette.groups.getOrNull(group)?.items?.forEach { item ->
                    Surface(
                        onClick = { insertTemplate(item) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.semantics { contentDescription = item.description },
                    ) {
                        EquationPreview(
                            item.label,
                            Modifier.width(if (item.label.length > LONG_LABEL) 104.dp else 60.dp).height(48.dp).padding(Spacing.XS),
                            maxSizeDp = 18.dp,
                            placeholders = false,
                        )
                    }
                }
            }

            // Size of the formula on the page.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Text(stringResource(R.string.equation_size, editSize.roundToInt()))
                Slider(
                    value = editSize,
                    onValueChange = { editSize = it },
                    valueRange = EquationElement.MIN_SIZE_PT * 2..MAX_SLIDER_PT,
                    modifier = Modifier.weight(1f),
                )
            }

            // Rewrite by hand: write a part again, read it, and it replaces the field's selection.
            if (!rewriting) {
                OutlinedButton({ rewriting = true }) {
                    Icon(Icons.Rounded.Draw, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.S))
                    Text(stringResource(R.string.equation_rewrite))
                }
            } else {
                Text(stringResource(R.string.equation_rewrite_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HandwritingPad(pad, Modifier.fillMaxWidth().heightIn(min = 140.dp).height(160.dp).background(MaterialTheme.colorScheme.surface))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton({ readPad(penPx) }, enabled = !rewriteBusy) { Text(stringResource(R.string.equation_read_pad)) }
                    TextButton(pad::clear) { Text(stringResource(R.string.equation_clear_pad)) }
                    TextButton({ rewriting = false; pad.clear() }) { Text(stringResource(R.string.dialog_cancel)) }
                    if (rewriteBusy) CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(Spacing.L))
        }
    }
}

private val GROUP_NAMES = listOf(
    R.string.equation_group_structures,
    R.string.equation_group_calculus,
    R.string.equation_group_matrices,
    R.string.equation_group_greek,
    R.string.equation_group_relations,
    R.string.equation_group_arrows,
)
private const val LONG_LABEL = 24
private const val MAX_SLIDER_PT = 96f
