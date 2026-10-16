package app.bestnote.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import androidx.compose.ui.res.stringResource
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperOrientation
import app.bestnote.core.model.PaperSizePreset
import app.bestnote.core.model.PaperSizes
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.canvas.document.PagedDocumentView
import kotlin.math.roundToInt

internal val PaperTemplate.label: String
    @Composable get() = stringResource(
        when (this) {
            PaperTemplate.Blank -> R.string.editor_paper_blank
            PaperTemplate.RuledNarrow -> R.string.editor_paper_narrow
            PaperTemplate.Ruled -> R.string.editor_paper_college
            PaperTemplate.RuledWide -> R.string.editor_paper_wide
            PaperTemplate.Grid -> R.string.editor_paper_grid
            PaperTemplate.Dotted -> R.string.editor_paper_dotted
            PaperTemplate.Cornell -> R.string.editor_paper_cornell
            PaperTemplate.MusicStaff -> R.string.editor_paper_music
            PaperTemplate.PlannerDaily -> R.string.editor_paper_planner_daily
            PaperTemplate.PlannerWeekly -> R.string.editor_paper_planner_weekly
            PaperTemplate.Graph -> R.string.editor_paper_graph
        },
    )

private val templateOrder = listOf(
    PaperTemplate.Blank, PaperTemplate.RuledNarrow, PaperTemplate.Ruled, PaperTemplate.RuledWide,
    PaperTemplate.Grid, PaperTemplate.Dotted, PaperTemplate.Graph, PaperTemplate.Cornell,
    PaperTemplate.MusicStaff, PaperTemplate.PlannerDaily, PaperTemplate.PlannerWeekly,
)

private val PaperSizePreset.label: String
    get() = when (this) {
        PaperSizePreset.A4 -> "A4"
        PaperSizePreset.A5 -> "A5"
        PaperSizePreset.Letter -> "Letter"
        PaperSizePreset.FitToScreen -> "Fit to screen"
        PaperSizePreset.Custom -> "Custom…"
    }

/**
 * Paper of the page being read (PAGES-01/02): template, paper color, size and orientation. Changes
 * apply to that page; "+ New page" then continues with the last page's paper.
 */
@Composable
internal fun PaperMenuButton(view: PagedDocumentView?, onManage: () -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf<Page?>(null) }
    Box {
        ChromeIconButton(AtelierIcons.Pages, "Paper", { open = true }, enabled = view != null, size = 44.dp)
        val doc = view?.document
        val index = view?.currentPage ?: -1
        if (open && doc != null && index in 0 until doc.size) {
            val page = doc.pages[index].page
            val update = { transform: (Page) -> Page -> doc.updatePage(index, transform) }
            val preset = PaperSizes.presetOf(page.size)
            val orientation = PaperSizes.orientationOf(page.size)
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                Choice("All pages…", false) {
                    open = false
                    onManage()
                }
                HorizontalDivider()
                Section("Page ${index + 1} · template")
                templateOrder.forEach { t ->
                    Choice(t.label, page.template == t) { update { it.copy(template = t) } }
                }
                HorizontalDivider()
                Section("Paper")
                PaperColor.entries.forEach { c ->
                    Choice(if (c == PaperColor.Dark) "Dark paper" else c.name, page.paperColor == c) { update { it.copy(paperColor = c) } }
                }
                HorizontalDivider()
                Section("Size")
                PaperSizePreset.entries.forEach { p ->
                    val selected = p == preset && p != PaperSizePreset.FitToScreen
                    Choice(p.label, selected) {
                        when (p) {
                            PaperSizePreset.Custom -> {
                                custom = page
                                open = false
                            }
                            else -> {
                                val size = PaperSizes.of(p, orientation, view?.viewportAspect ?: (842f / 595f))
                                update { it.copy(widthPt = size.widthPt, heightPt = size.heightPt) }
                            }
                        }
                    }
                }
                HorizontalDivider()
                PaperOrientation.entries.forEach { o ->
                    Choice(o.name, orientation == o) {
                        val size = PaperSizes.oriented(page.size, o)
                        update { it.copy(widthPt = size.widthPt, heightPt = size.heightPt) }
                    }
                }
            }
        }
    }
    custom?.let { page ->
        CustomSizeDialog(page.size, onDismiss = { custom = null }) { size ->
            view?.document?.updatePage(page.index) { it.copy(widthPt = size.widthPt, heightPt = size.heightPt) }
            custom = null
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = BestNoteTheme.type.overline,
        color = BestNoteTheme.colors.muted,
        modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.S),
    )
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Row {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(Spacing.L))
            }
        },
        trailingIcon = if (selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
        onClick = onClick,
    )
}

@Composable
private fun CustomSizeDialog(initial: PageSize, onDismiss: () -> Unit, onConfirm: (PageSize) -> Unit) {
    fun mm(pt: Float) = (pt / PaperSizes.PT_PER_MM).roundToInt().toString()
    var w by remember { mutableStateOf(mm(initial.widthPt)) }
    var h by remember { mutableStateOf(mm(initial.heightPt)) }
    val size = w.toFloatOrNull()?.let { ww -> h.toFloatOrNull()?.let { hh -> PageSize(ww * PaperSizes.PT_PER_MM, hh * PaperSizes.PT_PER_MM) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom page size") },
        text = {
            Row {
                OutlinedTextField(w, { w = it }, label = { Text("Width (mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.M))
                OutlinedTextField(h, { h = it }, label = { Text("Height (mm)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            }
        },
        confirmButton = {
            TextButton(onClick = { size?.let { onConfirm(PaperSizes.clamp(it)) } }, enabled = size != null) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
