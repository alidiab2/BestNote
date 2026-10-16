package app.bestnote.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CropLandscape
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.WidthClass
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.PaperTemplate

/**
 * LIB-02 "New notebook": title, cover, paper template and size, with a live preview of the notebook.
 * [folderName] says where it will be created (the open folder), if anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewNotebookSheet(folderName: String?, onDismiss: () -> Unit, onCreate: (NewNotebookSpec) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf("") }
    var coverIndex by rememberSaveable { mutableIntStateOf(0) }
    var template by rememberSaveable { mutableStateOf(PaperTemplates.Default) }
    var size by rememberSaveable { mutableStateOf(PaperSize.A4) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    val untitled = stringResource(R.string.library_new_title)
    val cover = CoverChoices.all[coverIndex]
    val format = PaperFormat(size, landscape)
    val create = { onCreate(NewNotebookSpec(title.trim().ifEmpty { untitled }, cover, template, format)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 900.dp,
        shape = BestNoteShapes.SheetTop,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 720.dp
            val phone = WidthClass.of(maxWidth) == WidthClass.Compact
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (phone) Spacing.L else Spacing.XXL)
                    .padding(bottom = Spacing.XXL)
                    .navigationBarsPadding(),
            ) {
                val heading: @Composable (Modifier) -> Unit = { mod ->
                    Column(mod) {
                        Text(stringResource(R.string.library_new_notebook), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            if (folderName != null) stringResource(R.string.library_new_in_folder, folderName) else stringResource(R.string.library_new_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BestNoteTheme.colors.muted,
                        )
                    }
                }
                val actions: @Composable () -> Unit = {
                    PillButton(stringResource(R.string.library_cancel), onDismiss, background = Color.Transparent, foreground = MaterialTheme.colorScheme.onSurfaceVariant, outlined = true)
                    Spacer(Modifier.width(Spacing.M))
                    PillButton(stringResource(R.string.library_create), create, icon = Icons.Outlined.Add)
                }
                if (phone) {
                    heading(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.M), verticalAlignment = Alignment.CenterVertically) { actions() }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        heading(Modifier.weight(1f))
                        actions()
                    }
                }
                Spacer(Modifier.height(Spacing.XL))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XXL)) {
                    if (wide) {
                        NotebookPreview(title.ifBlank { untitled }, cover, template, format, Modifier.width(220.dp).padding(top = Spacing.L))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                        SheetLabel(stringResource(R.string.library_new_title_label))
                        TitleField(title, { title = it }, untitled, onDone = create)
                        SheetLabel(stringResource(R.string.library_new_cover))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), contentPadding = PaddingValues(vertical = Spacing.XS)) {
                            items(CoverChoices.all.size) { i ->
                                CoverSwatch(title, CoverChoices.all[i], i == coverIndex, Modifier.size(66.dp, 90.dp)) { coverIndex = i }
                            }
                        }
                        SheetLabel(stringResource(R.string.library_new_paper))
                        TemplateGrid(template, format) { template = it }
                        SheetLabel(stringResource(R.string.library_new_size))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                            Segmented(PaperSize.entries.map { stringResource(it.label) to null }, PaperSize.entries.indexOf(size)) { size = PaperSize.entries[it] }
                            Segmented(
                                listOf(
                                    stringResource(R.string.library_portrait) to Icons.Outlined.CropPortrait,
                                    stringResource(R.string.library_landscape) to Icons.Outlined.CropLandscape,
                                ),
                                if (landscape) 1 else 0,
                            ) { landscape = it == 1 }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text.uppercase(), style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted, modifier = Modifier.padding(top = Spacing.M))
}

@Composable
private fun TitleField(value: String, onValue: (String) -> Unit, placeholder: String, onDone: () -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(BestNoteShapes.Control)
                    .background(MaterialTheme.colorScheme.surface)
                    .hairline(BestNoteShapes.Control)
                    .padding(horizontal = Spacing.L),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.titleLarge, color = BestNoteTheme.colors.muted)
                inner()
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateGrid(selected: PaperTemplate, format: PaperFormat, onSelect: (PaperTemplate) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        PaperTemplates.all.forEach { t ->
            val isSelected = t == selected
            val ring by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                Motion.tween(Motion.Duration.Short),
                label = "templateRing",
            )
            val label = PaperTemplates.label(t)?.let { stringResource(it) } ?: t.name
            Column(
                Modifier
                    .width(112.dp)
                    .clip(BestNoteShapes.Control)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(if (isSelected) 2.dp else Elevation.Hairline, ring, BestNoteShapes.Control)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(t) })
                    .semantics { this.selected = isSelected }
                    .padding(Spacing.S),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.height(96.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PaperPreview(t, Modifier.fillMaxHeight().aspectRatio(format.aspectRatio, matchHeightConstraintsFirst = true))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.S),
                )
            }
        }
    }
}

/** Pill segmented control (sizes, orientation). */
@Composable
private fun Segmented(options: List<Pair<String, ImageVector?>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.height(44.dp).clip(BestNoteShapes.Pill).background(MaterialTheme.colorScheme.surfaceContainer).padding(Spacing.XS),
        horizontalArrangement = Arrangement.spacedBy(Spacing.XXS),
    ) {
        options.forEachIndexed { i, (label, icon) ->
            val active = i == selected
            val bg by animateColorAsState(
                if (active) MaterialTheme.colorScheme.surfaceBright else Color.Transparent,
                Motion.tween(Motion.Duration.Short),
                label = "segment",
            )
            Row(
                Modifier
                    .fillMaxHeight()
                    .then(if (active) Modifier.softShadow(Elevation.Low, BestNoteShapes.Pill) else Modifier)
                    .clip(BestNoteShapes.Pill)
                    .background(bg)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(i) })
                    .semantics { this.selected = active }
                    .padding(horizontal = Spacing.M),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.XS),
            ) {
                val tint = if (active) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted
                if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
    }
}

/** The notebook being made: its cover, with the first page peeking out behind it. */
@Composable
private fun NotebookPreview(title: String, cover: CoverChoice, template: PaperTemplate, format: PaperFormat, modifier: Modifier = Modifier) {
    Box(modifier.height(260.dp)) {
        PaperPreview(
            template,
            Modifier
                .align(Alignment.TopEnd)
                .height(220.dp)
                .aspectRatio(format.aspectRatio, matchHeightConstraintsFirst = true)
                .offset(x = Spacing.S, y = Spacing.L),
        )
        NotebookCover(title, cover.colorIndex, Modifier.align(Alignment.TopStart).size(CoverWidth, CoverHeight), style = cover.style)
    }
}

/**
 * A miniature of [template] as printed paper (stays light in dark mode). Templates without a drawing
 * here show as blank paper.
 */
@Composable
internal fun PaperPreview(template: PaperTemplate, modifier: Modifier = Modifier) {
    val paper = BestNoteTheme.colors.paper
    val shape = RoundedCornerShape(4.dp)
    Canvas(modifier.softShadow(Elevation.Low, shape).clip(shape).background(paper.page)) {
        val step = size.width / PREVIEW_CELLS
        val line = 1.dp.toPx() * 0.8f
        when (template) {
            PaperTemplate.Ruled -> {
                var y = step * 2
                while (y < size.height) {
                    drawLine(paper.ruledLine, Offset(0f, y), Offset(size.width, y), line)
                    y += step
                }
                drawLine(paper.marginLine, Offset(step * 1.6f, 0f), Offset(step * 1.6f, size.height), line)
            }
            PaperTemplate.Grid -> {
                var x = step
                while (x < size.width) {
                    drawLine(paper.ruledLine, Offset(x, 0f), Offset(x, size.height), line)
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(paper.ruledLine, Offset(0f, y), Offset(size.width, y), line)
                    y += step
                }
            }
            PaperTemplate.Dotted -> {
                var y = step
                while (y < size.height) {
                    var x = step
                    while (x < size.width) {
                        drawCircle(paper.ruledLine.copy(alpha = 1f), radius = line * 1.1f, center = Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            PaperTemplate.Graph -> {
                val fine = step / 2f
                var i = 0
                var p = fine
                while (p < size.height) {
                    val c = if (i % 5 == 4) paper.marginLine.copy(alpha = 0.5f) else paper.ruledLine
                    drawLine(c, Offset(0f, p), Offset(size.width, p), line * 0.6f)
                    if (p < size.width) drawLine(c, Offset(p, 0f), Offset(p, size.height), line * 0.6f)
                    p += fine
                    i++
                }
            }
            PaperTemplate.Cornell -> {
                drawLine(paper.marginLine, Offset(0f, step * 1.5f), Offset(size.width, step * 1.5f), line)
                drawLine(paper.marginLine, Offset(size.width * 0.3f, step * 1.5f), Offset(size.width * 0.3f, size.height * 0.8f), line)
                drawLine(paper.marginLine, Offset(0f, size.height * 0.8f), Offset(size.width, size.height * 0.8f), line)
            }
            PaperTemplate.MusicStaff -> {
                var top = step * 1.2f
                while (top + step * 0.8f < size.height) {
                    for (l in 0 until 5) {
                        val y = top + l * step * 0.2f
                        drawLine(paper.ruledLine, Offset(step * 0.5f, y), Offset(size.width - step * 0.5f, y), line)
                    }
                    top += step * 1.8f
                }
            }
            PaperTemplate.PlannerDaily -> {
                val split = size.width * 0.62f
                drawLine(paper.marginLine, Offset(0f, step * 1.2f), Offset(size.width, step * 1.2f), line)
                drawLine(paper.marginLine, Offset(split, step * 1.2f), Offset(split, size.height), line)
                var y = step * 1.2f + step * 0.5f
                while (y < size.height) {
                    drawLine(paper.ruledLine, Offset(0f, y), Offset(size.width, y), line)
                    y += step * 0.5f
                }
            }
            PaperTemplate.PlannerWeekly -> {
                val top = step * 1.2f
                val cell = (size.height - top) / 4f
                drawLine(paper.marginLine, Offset(0f, top), Offset(size.width, top), line)
                for (r in 1..3) drawLine(paper.marginLine, Offset(0f, top + r * cell), Offset(size.width, top + r * cell), line)
                drawLine(paper.marginLine, Offset(size.width / 2f, top), Offset(size.width / 2f, size.height), line)
            }
            else -> Unit
        }
    }
}

private const val PREVIEW_CELLS = 10f
