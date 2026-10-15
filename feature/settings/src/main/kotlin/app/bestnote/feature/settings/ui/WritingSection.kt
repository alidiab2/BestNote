package app.bestnote.feature.settings.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bestnote.core.datastore.PaperSize
import app.bestnote.core.datastore.ToolbarDock
import app.bestnote.core.datastore.UserPreferences
import app.bestnote.core.datastore.WritingPreferences
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.InkPalette
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.hairline
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.PenStyle
import app.bestnote.feature.settings.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Default ink choices: the Atelier ink palette plus graphite, brown and violet. */
private val InkChoices = InkPalette.Defaults + listOf(Color(0xFF4A4D57), Color(0xFF8A5A2B), Color(0xFF6A4BC4))

@Composable
internal fun WritingSection(
    prefs: UserPreferences,
    update: ((WritingPreferences) -> WritingPreferences) -> Unit,
    updateRoot: ((UserPreferences) -> UserPreferences) -> Unit,
) {
    val writing = prefs.writing
    DetailHeader(stringResource(R.string.settings_cat_writing), stringResource(R.string.settings_writing_desc))

    SettingsGroup(stringResource(R.string.settings_default_pen)) {
        row {
            Row(Modifier.fillMaxWidth().padding(Spacing.L), verticalAlignment = Alignment.CenterVertically) {
                PenSample(writing, Modifier.weight(1f))
            }
        }
        row {
            StackedRow(stringResource(R.string.settings_pen_type)) {
                Segmented(
                    PenStyle.entries,
                    writing.penStyle,
                    label = {
                        when (it) {
                            PenStyle.Fountain -> stringResource(R.string.settings_pen_fountain)
                            PenStyle.Ball -> stringResource(R.string.settings_pen_ball)
                            PenStyle.Brush -> stringResource(R.string.settings_pen_brush)
                        }
                    },
                    onSelect = { v -> update { it.copy(penStyle = v) } },
                )
            }
        }
        row {
            StackedRow(stringResource(R.string.settings_pen_color)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                    InkChoices.forEach { color ->
                        Swatch(color, selected = color.toArgb() == writing.colorArgb) {
                            update { it.copy(colorArgb = color.toArgb()) }
                        }
                    }
                }
            }
        }
        row {
            StackedRow(stringResource(R.string.settings_pen_size), subtitle = stringResource(R.string.settings_pen_size_value, writing.size.roundToInt())) {
                Slider(
                    value = writing.size,
                    onValueChange = { v -> update { it.copy(size = (v * 2).roundToInt() / 2f) } },
                    valueRange = WritingPreferences.MIN_PEN_SIZE..WritingPreferences.MAX_PEN_SIZE,
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_new_notebooks)) {
        row {
            StackedRow(stringResource(R.string.settings_template)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    DefaultTemplates.forEach { template ->
                        TemplateCard(template, template == writing.template, Modifier.weight(1f)) {
                            update { it.copy(template = template) }
                        }
                    }
                }
            }
        }
        row {
            StackedRow(stringResource(R.string.settings_paper_size)) {
                Segmented(
                    PaperSize.entries,
                    writing.paperSize,
                    label = {
                        when (it) {
                            PaperSize.A4 -> stringResource(R.string.settings_paper_a4)
                            PaperSize.A5 -> stringResource(R.string.settings_paper_a5)
                            PaperSize.Letter -> stringResource(R.string.settings_paper_letter)
                        }
                    },
                    onSelect = { v -> update { it.copy(paperSize = v) } },
                )
            }
        }
    }

    SettingsGroup(stringResource(R.string.settings_layout_group)) {
        row {
            StackedRow(stringResource(R.string.settings_dock), subtitle = stringResource(R.string.settings_dock_sub), icon = Icons.Outlined.SpaceDashboard) {
                Segmented(
                    ToolbarDock.entries,
                    prefs.toolbarDock,
                    label = {
                        when (it) {
                            ToolbarDock.Top -> stringResource(R.string.settings_dock_top)
                            ToolbarDock.Left -> stringResource(R.string.settings_dock_left)
                            ToolbarDock.Right -> stringResource(R.string.settings_dock_right)
                        }
                    },
                    onSelect = { v -> updateRoot { it.copy(toolbarDock = v) } },
                )
            }
        }
        row {
            SwitchRow(
                stringResource(R.string.settings_left_handed),
                prefs.leftHanded,
                { v -> updateRoot { it.copy(leftHanded = v) } },
                subtitle = stringResource(R.string.settings_left_handed_sub),
                icon = Icons.Outlined.FormatAlignLeft,
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val ring by animateDpAsState(if (selected) 2.dp else 0.dp, Motion.Spring.snappy(), label = "swatch")
    val name = stringResource(R.string.settings_pen_color)
    Box(
        Modifier
            .size(SwatchSize)
            .clip(CircleShape)
            .then(if (ring > 0.dp) Modifier.border(ring, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .padding(SwatchInset)
            .clip(CircleShape)
            .background(color)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(16.dp))
    }
}

/** A handwritten-looking sample line in the chosen pen, on paper. */
@Composable
private fun PenSample(writing: WritingPreferences, modifier: Modifier) {
    val paper = BestNoteTheme.colors.paper
    val ink = Color(writing.colorArgb)
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(SampleAspect)
            .clip(BestNoteShapes.Control)
            .background(paper.page)
            .hairline(BestNoteShapes.Control),
    ) {
        val baseline = size.height * 0.66f
        drawLine(paper.ruledLine, Offset(0f, baseline + 2.dp.toPx()), Offset(size.width, baseline + 2.dp.toPx()), 1.dp.toPx())
        // Cursive "eeee" loops: a prolate trochoid, like joined handwriting.
        val w = writing.size * SizeToDp
        val loopR = size.height * 0.22f
        val advance = loopR * 0.6f
        val left = size.width * 0.06f
        val loops = ((size.width * 0.88f) / (advance * 2 * PI.toFloat())).toInt().coerceAtLeast(1)
        val steps = loops * StepsPerLoop
        var prev: Offset? = null
        for (i in 0..steps) {
            val t = i.toFloat() / StepsPerLoop * 2 * PI.toFloat()
            val pt = Offset(left + advance * t - loopR * sin(t), baseline - loopR * (1f + cos(t)))
            prev?.let { p0 ->
                val down = (pt.y - p0.y) > 0
                val factor = when (writing.penStyle) {
                    PenStyle.Ball -> 1f
                    PenStyle.Fountain -> if (down) 1.35f else 0.7f
                    PenStyle.Brush -> 0.5f + 1.3f * ((sin(t) + 1f) / 2f)
                }
                drawLine(ink, p0, pt, strokeWidth = (w * factor).dp.toPx(), cap = StrokeCap.Round)
            }
            prev = pt
        }
    }
}

@Composable
private fun TemplateCard(template: PaperTemplate, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val paper = BestNoteTheme.colors.paper
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val label = when (template) {
        PaperTemplate.Blank -> stringResource(R.string.settings_template_blank)
        PaperTemplate.Ruled -> stringResource(R.string.settings_template_ruled)
        PaperTemplate.Grid -> stringResource(R.string.settings_template_grid)
        PaperTemplate.Dotted -> stringResource(R.string.settings_template_dotted)
        else -> template.name
    }
    Column(
        modifier
            .clip(BestNoteShapes.Control)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(Spacing.XS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .width(TemplateWidth)
                .aspectRatio(PageAspect)
                .clip(BestNoteShapes.Control)
                .background(paper.page)
                .border(if (selected) 2.dp else 1.dp, border, BestNoteShapes.Control),
        ) {
            val gap = size.width / TemplateDivisions
            when (template) {
                PaperTemplate.Blank -> Unit
                PaperTemplate.Ruled -> {
                    var y = gap * 2
                    while (y < size.height) {
                        drawLine(paper.ruledLine, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        y += gap
                    }
                    drawLine(paper.marginLine, Offset(gap * 1.6f, 0f), Offset(gap * 1.6f, size.height), 1.dp.toPx())
                }
                PaperTemplate.Grid -> {
                    var p = gap
                    while (p < size.height) {
                        drawLine(paper.ruledLine, Offset(0f, p), Offset(size.width, p), 1.dp.toPx())
                        if (p < size.width) drawLine(paper.ruledLine, Offset(p, 0f), Offset(p, size.height), 1.dp.toPx())
                        p += gap
                    }
                }
                PaperTemplate.Dotted -> {
                    var y = gap
                    while (y < size.height) {
                        var x = gap
                        while (x < size.width) {
                            drawCircle(DotColor, radius = 1.2f.dp.toPx(), center = Offset(x, y))
                            x += gap
                        }
                        y += gap
                    }
                }
                else -> Unit
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.S),
        )
    }
}

private val SwatchSize = 36.dp
private val SwatchInset = 4.dp
private const val SampleAspect = 5f
private const val SizeToDp = 0.45f
private const val StepsPerLoop = 48
private val TemplateWidth = 64.dp
private const val PageAspect = 0.707f
private const val TemplateDivisions = 7f
private val DotColor = Color(0xFFB9C2D0)

/** The default-template picker offers the four basic papers; the editor's Pages menu has the rest. */
private val DefaultTemplates = listOf(PaperTemplate.Blank, PaperTemplate.Ruled, PaperTemplate.Grid, PaperTemplate.Dotted)
