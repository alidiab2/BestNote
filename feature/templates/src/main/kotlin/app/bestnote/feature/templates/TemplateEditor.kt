package app.bestnote.feature.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.fonts.FontPickerDialog
import app.bestnote.core.fonts.FontRegistry
import app.bestnote.core.fonts.FontScript
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.Decoration
import app.bestnote.engine.layout.DetailLevel
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.RoleStyle
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.TermsStyle
import app.bestnote.engine.templates.TemplateClamp
import app.bestnote.engine.templates.TemplateJson

private const val TAB_LOOK = 0
private const val TAB_AI = 1
private const val TAB_ELEMENTS = 2
private const val TAB_DESIGN = 3

/** The template editor: live preview + Look / AI instructions / Elements / Design with AI. */
@Composable
internal fun TemplateEditor(editor: EditorState, vm: TemplatesViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_LOOK) }
    val draft = editor.draft
    val update = vm::update
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val controls: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                OutlinedTextField(
                    draft.name, { n -> update { it.copy(name = n.take(TemplateClamp.MAX_NAME)) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.L, vertical = Spacing.S),
                    singleLine = true, label = { Text(stringResource(R.string.tpl_name)) },
                )
                TabRow(tab) {
                    listOf(R.string.tpl_tab_look, R.string.tpl_tab_ai, R.string.tpl_tab_elements, R.string.tpl_tab_design).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        TAB_LOOK -> Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) { LookSection(draft, update) }
                        TAB_AI -> Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) { AiSection(draft, update) }
                        TAB_ELEMENTS -> Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.XS)) { ElementsSection(draft, update) }
                        else -> DesignChat(editor, vm)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(Spacing.M), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (editor.dirty) Text(stringResource(R.string.tpl_unsaved), style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::close) { Text(stringResource(R.string.tpl_close)) }
                    Spacer(Modifier.width(Spacing.S))
                    Button(onClick = vm::save, enabled = editor.dirty) { Text(stringResource(R.string.tpl_save)) }
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(0.9f).fillMaxHeight().padding(Spacing.L), contentAlignment = Alignment.TopCenter) {
                    TemplatePreview(draft, Modifier.fillMaxWidth())
                }
                controls(Modifier.weight(1.1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.TopCenter) {
                    TemplatePreview(draft, Modifier.fillMaxHeight().padding(vertical = Spacing.S))
                }
                controls(Modifier.weight(1f))
            }
        }
    }
}


@Composable
internal fun roleName(r: NoteRole) = stringResource(
    when (r) {
        NoteRole.Title -> R.string.tpl_role_title
        NoteRole.Heading -> R.string.tpl_role_heading
        NoteRole.Subheading -> R.string.tpl_role_subheading
        NoteRole.Definition -> R.string.tpl_role_definition
        NoteRole.Theorem -> R.string.tpl_role_theorem
        NoteRole.Formula -> R.string.tpl_role_formula
        NoteRole.Result -> R.string.tpl_role_result
        NoteRole.Example -> R.string.tpl_role_example
        NoteRole.Step -> R.string.tpl_role_step
        NoteRole.Note -> R.string.tpl_role_note
        NoteRole.Warning -> R.string.tpl_role_warning
        NoteRole.Margin -> R.string.tpl_role_margin
        NoteRole.Body -> R.string.tpl_role_body
    },
)

@Composable
private fun decorationName(d: Decoration) = stringResource(
    when (d) {
        Decoration.None -> R.string.tpl_deco_none
        Decoration.Underline -> R.string.tpl_deco_underline
        Decoration.DoubleUnderline -> R.string.tpl_deco_double
        Decoration.Highlight -> R.string.tpl_deco_highlight
        Decoration.Box -> R.string.tpl_deco_box
    },
)

@Composable
private fun SectionTitle(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.S))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookSection(t: NoteTemplate, update: ((NoteTemplate) -> NoteTemplate) -> Unit) {
    val registry = FontRegistry.get(androidx.compose.ui.platform.LocalContext.current)
    fun look(change: (app.bestnote.engine.layout.StyleTemplate) -> app.bestnote.engine.layout.StyleTemplate) = update { it.copy(look = change(it.look)) }
    SectionTitle(R.string.tpl_look_colors)
    Text(stringResource(R.string.tpl_look_colors_hint), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
    NoteRole.entries.forEach { r -> RoleRow(r, t.look.role(r)) { s -> look { l -> l.copy(roles = l.roles + (r to s)) } } }

    SectionTitle(R.string.tpl_look_fonts)
    FontRow(FontScript.Latin, t.look.latinFont.id, registry) { id -> look { it.copy(latinFont = HandFont(id)) } }
    FontRow(FontScript.Arabic, t.look.arabicFont.id, registry) { id -> look { it.copy(arabicFont = HandFont(id)) } }
    FontRow(FontScript.Hebrew, t.look.hebrewFont.id, registry) { id -> look { it.copy(hebrewFont = HandFont(id)) } }

    SectionTitle(R.string.tpl_look_sizes)
    LabeledSlider(R.string.tpl_look_body_size, t.look.bodyScale, TemplateClamp.MIN_BODY..TemplateClamp.MAX_BODY) { v -> look { it.copy(bodyScale = v) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tpl_look_space_before), Modifier.weight(1f))
        OutlinedButton(onClick = { look { it.copy(spaceBeforeHeading = (it.spaceBeforeHeading - 1).coerceAtLeast(0)) } }) { Text("−") }
        Text("${t.look.spaceBeforeHeading}", Modifier.padding(horizontal = Spacing.M), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { look { it.copy(spaceBeforeHeading = (it.spaceBeforeHeading + 1).coerceAtMost(TemplateClamp.MAX_SPACE_BEFORE)) } }) { Text("+") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tpl_look_margin_arrows), Modifier.weight(1f))
        Switch(t.look.marginArrows, { v -> look { it.copy(marginArrows = v) } })
    }

    SectionTitle(R.string.tpl_look_paper)
    Text(stringResource(R.string.tpl_look_paper_hint), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        FilterChip(t.look.paper == null, { look { it.copy(paper = null) } }, label = { Text(stringResource(R.string.tpl_paper_notebook)) })
        RuledPaper.writable.forEach { p ->
            FilterChip(t.look.paper == p, { look { it.copy(paper = p) } }, label = { Text(paperName(p)) })
        }
    }
}

@Composable
private fun paperName(p: PaperTemplate) = stringResource(
    when (p) {
        PaperTemplate.Ruled -> R.string.tpl_paper_ruled
        PaperTemplate.RuledNarrow -> R.string.tpl_paper_narrow
        PaperTemplate.RuledWide -> R.string.tpl_paper_wide
        PaperTemplate.Grid -> R.string.tpl_paper_grid
        PaperTemplate.Dotted -> R.string.tpl_paper_dotted
        PaperTemplate.Cornell -> R.string.tpl_paper_cornell
        else -> R.string.tpl_paper_blank
    },
)

@Composable
private fun FontRow(script: FontScript, fontId: String, registry: FontRegistry, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val family = app.bestnote.core.fonts.rememberFontFamily(fontId, registry)
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = Spacing.XS), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(when (script) { FontScript.Latin -> R.string.tpl_font_latin; FontScript.Arabic -> R.string.tpl_font_arabic; FontScript.Hebrew -> R.string.tpl_font_hebrew }),
                style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted,
            )
            Text(script.sample, style = MaterialTheme.typography.titleMedium.copy(fontFamily = family ?: MaterialTheme.typography.titleMedium.fontFamily), maxLines = 1)
        }
        Text(registry.nameOf(fontId), style = MaterialTheme.typography.labelLarge)
    }
    if (open) FontPickerDialog(script, fontId, onSelect = { id -> if (id != null) onPick(id); open = false }, onDismiss = { open = false }, registry = registry)
}

@Composable
private fun LabeledSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(stringResource(label), Modifier.weight(1f))
            Text("%.2f".format(value), style = MaterialTheme.typography.labelLarge)
        }
        Slider(value.coerceIn(range), onChange, valueRange = range)
    }
}

private val Palette = listOf(
    0xFF1E2A5AL, 0xFF2A4BD7L, 0xFF00796BL, 0xFF2F7D57L, 0xFF7B3FB5L, 0xFFD9462BL,
    0xFFE07B00L, 0xFF8D5524L, 0xFF5F6670L, 0xFF1E2433L, 0xFFC2185BL, 0xFF0097A7L,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleRow(role: NoteRole, style: RoleStyle, onChange: (RoleStyle) -> Unit) {
    var open by rememberSaveable(role.name) { mutableStateOf(false) }
    val pen = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = Spacing.XS), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(style.color?.let { Color(it) } ?: pen).border(1.dp, BestNoteTheme.colors.muted, CircleShape))
            Spacer(Modifier.width(Spacing.M))
            Text(roleName(role), Modifier.weight(1f))
            Text(decorationName(style.decoration) + " · ×%.2f".format(style.scale), style = MaterialTheme.typography.labelMedium, color = BestNoteTheme.colors.muted)
        }
        if (open) Column(Modifier.padding(start = Spacing.XL, bottom = Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                FilterChip(style.color == null, { onChange(style.copy(color = null)) }, label = { Text(stringResource(R.string.tpl_color_pen)) })
                Palette.forEach { c ->
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                            .border(if (style.color == c) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable { onChange(style.copy(color = c)) }
                            .semantics { contentDescription = TemplateJson.hex(c) },
                    )
                }
            }
            HexField(style.color) { onChange(style.copy(color = it)) }
            LabeledSlider(R.string.tpl_size, style.scale, TemplateClamp.MIN_ROLE_SCALE..TemplateClamp.MAX_ROLE_SCALE) { onChange(style.copy(scale = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Decoration.entries.forEach { d -> FilterChip(style.decoration == d, { onChange(style.copy(decoration = d)) }, label = { Text(decorationName(d)) }) }
            }
        }
    }
}

@Composable
private fun HexField(color: Long?, onColor: (Long) -> Unit) {
    var text by remember(color) { mutableStateOf(color?.let(TemplateJson::hex).orEmpty()) }
    OutlinedTextField(
        text,
        { v ->
            text = v.take(9)
            TemplateJson.parseColor(v)?.let(onColor)
        },
        singleLine = true,
        label = { Text(stringResource(R.string.tpl_color_hex)) },
        modifier = Modifier.width(180.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSection(t: NoteTemplate, update: ((NoteTemplate) -> NoteTemplate) -> Unit) {
    fun ai(change: (app.bestnote.engine.layout.AiInstructions) -> app.bestnote.engine.layout.AiInstructions) = update { it.copy(ai = change(it.ai)) }
    val langs = listOf<Pair<String?, Int>>(null to R.string.tpl_lang_board, "en" to R.string.tpl_lang_en, "ar" to R.string.tpl_lang_ar, "he" to R.string.tpl_lang_he)
    SectionTitle(R.string.tpl_ai_notes_language)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        langs.forEach { (code, label) -> FilterChip(t.ai.notesLanguage == code, { ai { it.copy(notesLanguage = code) } }, label = { Text(stringResource(label)) }) }
        val custom = t.ai.notesLanguage?.takeIf { c -> langs.none { it.first == c } }
        custom?.let { FilterChip(true, {}, label = { Text(it) }) }
    }
    SectionTitle(R.string.tpl_ai_terms_language)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        listOf<Pair<String?, Int>>(null to R.string.tpl_lang_none, "he" to R.string.tpl_lang_he, "ar" to R.string.tpl_lang_ar, "en" to R.string.tpl_lang_en).forEach { (code, label) ->
            FilterChip(t.ai.termsLanguage == code, { ai { it.copy(termsLanguage = code) } }, label = { Text(stringResource(label)) })
        }
    }
    if (t.ai.termsLanguage != null) {
        SectionTitle(R.string.tpl_ai_terms_style)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            TermsStyle.entries.forEach { s ->
                FilterChip(
                    t.ai.termsStyle == s, { ai { it.copy(termsStyle = s) } },
                    label = { Text(stringResource(when (s) { TermsStyle.InParentheses -> R.string.tpl_terms_paren; TermsStyle.Only -> R.string.tpl_terms_only; TermsStyle.None -> R.string.tpl_terms_none })) },
                )
            }
        }
    }
    SectionTitle(R.string.tpl_ai_detail)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        DetailLevel.entries.forEach { d ->
            FilterChip(
                t.ai.detail == d, { ai { it.copy(detail = d) } },
                label = { Text(stringResource(when (d) { DetailLevel.Summary -> R.string.tpl_detail_summary; DetailLevel.Full -> R.string.tpl_detail_full; DetailLevel.CheatSheet -> R.string.tpl_detail_cheat })) },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tpl_ai_step_by_step), Modifier.weight(1f))
        Switch(t.ai.stepByStep, { v -> ai { it.copy(stepByStep = v) } })
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tpl_ai_glossary), Modifier.weight(1f))
        Switch(t.ai.glossary, { v -> ai { it.copy(glossary = v) } })
    }
    SectionTitle(R.string.tpl_ai_note)
    OutlinedTextField(
        t.ai.note, { v -> ai { it.copy(note = v.take(TemplateClamp.MAX_NOTE)) } },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "ai-note" },
        minLines = 3, maxLines = 8,
        placeholder = { Text(stringResource(R.string.tpl_ai_note_hint)) },
        supportingText = { Text("${t.ai.note.length}/${TemplateClamp.MAX_NOTE}", textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
    )
}

@Composable
private fun elementName(e: NoteElement) = stringResource(
    when (e) {
        NoteElement.Title -> R.string.tpl_el_title
        NoteElement.Heading -> R.string.tpl_el_heading
        NoteElement.Paragraph -> R.string.tpl_el_paragraph
        NoteElement.Bullets -> R.string.tpl_el_bullets
        NoteElement.Definition -> R.string.tpl_el_definition
        NoteElement.Theorem -> R.string.tpl_el_theorem
        NoteElement.Formula -> R.string.tpl_el_formula
        NoteElement.KeyResult -> R.string.tpl_el_keyresult
        NoteElement.Example -> R.string.tpl_el_example
        NoteElement.StepSolution -> R.string.tpl_el_steps
        NoteElement.Note -> R.string.tpl_el_note
        NoteElement.Warning -> R.string.tpl_el_warning
        NoteElement.MarginRemark -> R.string.tpl_el_margin
        NoteElement.Comparison -> R.string.tpl_el_comparison
        NoteElement.Table -> R.string.tpl_el_table
        NoteElement.Summary -> R.string.tpl_el_summary
        NoteElement.Glossary -> R.string.tpl_el_glossary
        NoteElement.Diagram -> R.string.tpl_el_diagram
        NoteElement.Columns -> R.string.tpl_el_columns
        NoteElement.NumberedFormula -> R.string.tpl_el_numbered
        NoteElement.VariableLegend -> R.string.tpl_el_legend
        NoteElement.Derivation -> R.string.tpl_el_derivation
        NoteElement.WorkedExample -> R.string.tpl_el_worked
        NoteElement.StarNote -> R.string.tpl_el_star
    },
)

@Composable
private fun ElementsSection(t: NoteTemplate, update: ((NoteTemplate) -> NoteTemplate) -> Unit) {
    Text(stringResource(R.string.tpl_elements_hint), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
    NoteElement.entries.forEach { e ->
        val on = e in t.elements
        val locked = e in TemplateClamp.required
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(elementName(e), Modifier.weight(1f))
            Switch(on, { v -> update { it.copy(elements = if (v) it.elements + e else it.elements - e) } }, enabled = !locked)
        }
    }
}
