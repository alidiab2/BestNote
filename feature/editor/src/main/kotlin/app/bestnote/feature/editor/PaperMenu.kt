package app.bestnote.feature.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperColor
import app.bestnote.core.model.PaperOrientation
import app.bestnote.core.model.PaperSizePreset
import app.bestnote.core.model.PaperSizes
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.engine.canvas.document.PaperPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal val PaperColor.label: String
    @Composable get() = stringResource(
        when (this) {
            PaperColor.White -> R.string.paper_white
            PaperColor.Cream -> R.string.paper_cream
            PaperColor.Dark -> R.string.paper_dark
        },
    )

private val PaperGroup.label: String
    @Composable get() = stringResource(
        when (this) {
            PaperGroup.Basic -> R.string.paper_group_basic
            PaperGroup.Study -> R.string.paper_group_study
            PaperGroup.Planning -> R.string.paper_group_planning
            PaperGroup.Music -> R.string.paper_group_music
        },
    )

internal fun groupOf(template: PaperTemplate): PaperGroup =
    PageOps.groups.entries.firstOrNull { template in it.value }?.key ?: PaperGroup.Basic

/** Tiles per row in the template grid, and rows kept (so switching groups never resizes the sheet). */
private const val TILE_COLUMNS = 3
private const val TILE_ROWS = 2

/** Two panes side by side from this window width; one scrolling column below it (DS-05). */
private val TwoPaneMinWidth = 840.dp

/**
 * The Paper button and its sheet (PAGES-01/02/10): a floating sheet that points at the button, with
 * large live previews of every template on the notebook's own paper (grouped Basic · Study ·
 * Planning · Music), paper color, size and orientation, the scope a change applies to (this page,
 * all pages as one undo step, or pages added from now on), and inserting pages from a PDF or an image.
 */
@Composable
internal fun PaperMenuButton(view: PagedDocumentView?, onManage: () -> Unit = {}, insert: PageInsertFlow? = null) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf<Page?>(null) }
    var customScope by remember { mutableStateOf(PaperScope.ThisPage) }
    Box {
        ChromeIconButton(AtelierIcons.Paper, stringResource(R.string.editor_paper), { open = !open }, enabled = view != null, size = 44.dp)
        val doc = view?.document
        val index = view?.currentPage ?: -1
        if (open && doc != null && index in 0 until doc.size) {
            AnchoredPopover(TraySide.Top, onDismissed = { open = false }) { dismiss ->
                PaperSheet(
                    document = doc,
                    index = index,
                    viewportAspect = view.viewportAspect,
                    onCustomSize = { page, scope ->
                        custom = page
                        customScope = scope
                        dismiss()
                    },
                    onInsertPdf = insert?.let { flow -> { dismiss(); flow.startPdf(index) } },
                    onInsertImage = insert?.let { flow -> { dismiss(); flow.startImage(index) } },
                    onManage = { dismiss(); onManage() },
                )
            }
        }
    }
    custom?.let { page ->
        CustomSizeDialog(page.size, onDismiss = { custom = null }) { size ->
            view?.document?.let { doc -> applyPaper(doc, customScope, page.index, page) { it.copy(widthPt = size.widthPt, heightPt = size.heightPt) } }
            custom = null
        }
    }
}

private fun applyPaper(doc: PagedDocument, scope: PaperScope, index: Int, shown: Page, transform: (Page) -> Page) {
    PageOps.applyPaper(doc, scope, index, shown, transform)
}

@Composable
private fun PaperSheet(
    document: PagedDocument,
    index: Int,
    viewportAspect: Float,
    onCustomSize: (Page, PaperScope) -> Unit,
    onInsertPdf: (() -> Unit)?,
    onInsertImage: (() -> Unit)?,
    onManage: () -> Unit,
) {
    var rev by remember { mutableIntStateOf(0) }
    DisposableEffect(document) {
        val listener = object : PagedDocument.Listener {
            override fun onPagesChanged() {
                rev++
            }

            override fun onNewPagePaperChanged() {
                rev++
            }
        }
        document.addListener(listener)
        onDispose { document.removeListener(listener) }
    }
    var scope by remember { mutableStateOf(PaperScope.ThisPage) }
    // What the sheet shows: the page being read, or for "New pages" the paper new pages will get.
    val current = remember(rev, index) { document.pages.getOrNull(index)?.page }
    val page = (if (scope == PaperScope.NewPages) document.newPagePaper ?: document.pages.lastOrNull()?.page else current) ?: return
    var group by remember { mutableStateOf(groupOf(page.template)) }
    fun apply(transform: (Page) -> Page) {
        applyPaper(document, scope, index, page, transform)
        rev++
    }

    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowWidth = with(density) { window.width.toDp() }
    val twoPane = windowWidth >= TwoPaneMinWidth
    val maxHeight = (with(density) { window.height.toDp() } - 140.dp).coerceIn(320.dp, 720.dp)

    val templates: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            GroupTabs(group) { group = it }
            TemplateGrid(PageOps.groups.getValue(group), page) { t -> apply { it.copy(template = t) } }
        }
    }
    val options: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            SheetOverline(stringResource(R.string.paper_apply_to))
            ScopeSwitch(scope) { scope = it }
            if (scope == PaperScope.NewPages) {
                Text(stringResource(R.string.paper_scope_new_hint), style = BestNoteTheme.type.caption, color = BestNoteTheme.colors.muted)
            }
            Spacer(Modifier.height(Spacing.XXS))
            SheetOverline(stringResource(R.string.paper_color))
            PaperColorChips(page) { c -> apply { it.copy(paperColor = c) } }
            Spacer(Modifier.height(Spacing.XXS))
            SheetOverline(stringResource(R.string.paper_size))
            SizeChips(page, viewportAspect, onCustom = { onCustomSize(page, scope) }) { size -> apply { it.copy(widthPt = size.widthPt, heightPt = size.heightPt) } }
        }
    }
    val actions: @Composable () -> Unit = {
        Column {
            onInsertPdf?.let { SheetAction(AtelierIcons.Pdf, stringResource(R.string.paper_insert_pdf), it) }
            onInsertImage?.let { SheetAction(AtelierIcons.Image, stringResource(R.string.paper_insert_image), it) }
            SheetAction(AtelierIcons.Pages, stringResource(R.string.paper_manage), onManage)
        }
    }
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stringResource(R.string.editor_paper),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(Spacing.S))
            Text(
                stringResource(R.string.editor_page_of, index + 1, document.size),
                style = BestNoteTheme.type.caption,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }

    if (twoPane) {
        Row(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(Spacing.XL)) {
            Column(Modifier.width(TileWidth * TILE_COLUMNS + Spacing.M * (TILE_COLUMNS - 1)), verticalArrangement = Arrangement.spacedBy(Spacing.L)) {
                header()
                templates()
            }
            Spacer(Modifier.width(Spacing.XL))
            Box(Modifier.width(1.dp).height(TileGridHeight + 96.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(Spacing.XL))
            Column(Modifier.width(272.dp), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                options()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = Spacing.XS))
                actions()
            }
        }
    } else {
        val width = (windowWidth - 48.dp).coerceAtMost(TileWidth * TILE_COLUMNS + Spacing.M * (TILE_COLUMNS - 1) + Spacing.XL * 2)
        Column(
            Modifier.width(width).heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(Spacing.L),
        ) {
            header()
            templates()
            options()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            actions()
        }
    }
}

private val TileWidth = 124.dp
private const val TILE_ASPECT = 0.74f
private val TileLabel = 34.dp
private val TilePadding = 5.dp

/** Just the paper (no ink) of [page], [widthPx] wide, drawn by the canvas's own paper renderer. */
private fun renderPaper(page: Page, widthPx: Int, density: Float): android.graphics.Bitmap {
    val scale = widthPx / page.widthPt
    val heightPx = (page.heightPt * scale).roundToInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
    PaperPainter.draw(android.graphics.Canvas(bitmap), page, scale, widthPx, heightPx, density)
    return bitmap
}
private val TileGridHeight = (TileWidth / TILE_ASPECT + TileLabel) * TILE_ROWS + Spacing.M * (TILE_ROWS - 1)

@Composable
internal fun SheetOverline(text: String) {
    Text(text.uppercase(), style = BestNoteTheme.type.overline, color = BestNoteTheme.colors.muted)
}

/** Basic · Study · Planning · Music: underlined text tabs. */
@Composable
internal fun GroupTabs(selected: PaperGroup, onSelect: (PaperGroup) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.L)) {
        PaperGroup.entries.forEach { g ->
            val on = g == selected
            val color by animateColorAsState(
                if (on) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted,
                Motion.fade(),
                label = "tab",
            )
            Column(
                Modifier
                    .clip(BestNoteShapes.Control)
                    .clickable(role = Role.Tab) { onSelect(g) }
                    .semantics { this.selected = on }
                    .padding(horizontal = Spacing.XS, vertical = Spacing.XS),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(g.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = color)
                Spacer(Modifier.height(Spacing.XS))
                Box(
                    Modifier
                        .size(18.dp, 3.dp)
                        .clip(BestNoteShapes.Pill)
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemplateGrid(templates: List<PaperTemplate>, page: Page, onPick: (PaperTemplate) -> Unit) {
    // A fixed height (two rows) so switching groups never resizes the sheet under the finger.
    FlowRow(
        Modifier.height(TileGridHeight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalArrangement = Arrangement.spacedBy(Spacing.M),
        maxItemsInEachRow = TILE_COLUMNS,
    ) {
        templates.forEach { t -> TemplateTile(t, page, selected = t == page.template) { onPick(t) } }
    }
}

/** A template drawn by the real paper renderer, on the page's paper color, size and orientation. */
@Composable
internal fun TemplateTile(template: PaperTemplate, base: Page, selected: Boolean, width: Dp = TileWidth, onClick: () -> Unit) {
    val paper = base.copy(template = template, pdf = null)
    // Rendered at the tile's exact pixel size, so 1 px rules stay crisp instead of being scaled away.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val aspect = paper.widthPt / paper.heightPt
    val px = with(density) {
        val inner = width - TilePadding * 2
        (if (aspect >= TILE_ASPECT) inner else inner / TILE_ASPECT * aspect).roundToPx().coerceAtLeast(1)
    }
    val preview by produceState<ImageBitmap?>(null, template, base.paperColor, base.widthPt, base.heightPt, px) {
        value = withContext(Dispatchers.Default) { renderPaper(paper, px, density.density).asImageBitmap() }
    }
    val name = template.label
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, Motion.fade(), label = "ring")
    Column(
        Modifier
            .width(width)
            .clip(BestNoteShapes.Control)
            .clickable(onClickLabel = name, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = name
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(TILE_ASPECT)
                .border(2.dp, ring, RoundedCornerShape(10.dp))
                .padding(TilePadding),
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(4.dp)
            val fit = Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < TILE_ASPECT)
            Box(
                fit
                    .softShadow(Elevation.Low, shape)
                    .clip(shape)
                    .background(Color(PaperPainter.pageColor(paper)))
                    .border(Dp.Hairline, MaterialTheme.colorScheme.outlineVariant, shape),
            ) {
                preview?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(AtelierIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp)) }
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(TileLabel).padding(top = Spacing.XS),
        )
    }
}

/** This page · All pages · New pages, as a segmented pill. */
@Composable
private fun ScopeSwitch(scope: PaperScope, onScope: (PaperScope) -> Unit) {
    val options = listOf(
        PaperScope.ThisPage to R.string.paper_scope_this,
        PaperScope.AllPages to R.string.paper_scope_all,
        PaperScope.NewPages to R.string.paper_scope_new,
    )
    Segmented(options.map { (s, label) -> Triple(stringResource(label), s == scope) { onScope(s) } })
}

/** A pill of equal segments; the chosen one is a raised chip. */
@Composable
internal fun Segmented(options: List<Triple<String, Boolean, () -> Unit>>, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(BestNoteShapes.Pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
    ) {
        options.forEach { (label, on, onClick) ->
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.surface else Color.Transparent, Motion.fade(), label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .then(if (on) Modifier.softShadow(Elevation.Low, BestNoteShapes.Pill) else Modifier)
                    .clip(BestNoteShapes.Pill)
                    .background(bg)
                    .clickable(role = Role.Tab, onClick = onClick)
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = Spacing.XS),
                )
            }
        }
    }
}

/** White · Cream · Dark: the real paper colors as swatches. */
@Composable
internal fun PaperColorChips(page: Page, onColor: (PaperColor) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PaperColor.entries.forEach { c ->
            val on = page.paperColor == c
            val swatch = Color(PaperPainter.pageColor(page.copy(paperColor = c, pdf = null)))
            val label = c.label
            val selectedLabel = stringResource(R.string.paper_selected, label)
            Row(
                Modifier
                    .weight(1f)
                    .clip(BestNoteShapes.Pill)
                    .border(if (on) 2.dp else Dp.Hairline, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, BestNoteShapes.Pill)
                    .clickable(role = Role.RadioButton) { onColor(c) }
                    .semantics(mergeDescendants = true) {
                        selected = on
                        contentDescription = if (on) selectedLabel else label
                    }
                    .padding(start = 6.dp, end = Spacing.S, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private val PaperSizePreset.shortLabel: String
    @Composable get() = when (this) {
        PaperSizePreset.A4 -> "A4"
        PaperSizePreset.A5 -> "A5"
        PaperSizePreset.Letter -> "Letter"
        PaperSizePreset.FitToScreen -> stringResource(R.string.paper_fit)
        PaperSizePreset.Custom -> stringResource(R.string.paper_custom)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeChips(page: Page, viewportAspect: Float, onCustom: () -> Unit, onSize: (PageSize) -> Unit) {
    val preset = PaperSizes.presetOf(page.size)
    val orientation = PaperSizes.orientationOf(page.size)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PaperSizePreset.entries.forEach { p ->
            val on = p == preset && p != PaperSizePreset.FitToScreen
            Chip(p.shortLabel, on) {
                if (p == PaperSizePreset.Custom) onCustom() else onSize(PaperSizes.of(p, orientation, viewportAspect))
            }
        }
    }
    Segmented(
        listOf(
            Triple(stringResource(R.string.paper_portrait), orientation == PaperOrientation.Portrait) { onSize(PaperSizes.oriented(page.size, PaperOrientation.Portrait)) },
            Triple(stringResource(R.string.paper_landscape), orientation == PaperOrientation.Landscape) { onSize(PaperSizes.oriented(page.size, PaperOrientation.Landscape)) },
        ),
    )
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(BestNoteShapes.Pill)
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(Dp.Hairline, if (on) Color.Transparent else MaterialTheme.colorScheme.outlineVariant, BestNoteShapes.Pill)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = Spacing.M, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(BestNoteShapes.Control)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = Spacing.S, vertical = Spacing.M),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(BestNoteShapes.Control).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(Spacing.M))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CustomSizeDialog(initial: PageSize, onDismiss: () -> Unit, onConfirm: (PageSize) -> Unit) {
    fun mm(pt: Float) = (pt / PaperSizes.PT_PER_MM).roundToInt().toString()
    var w by remember { mutableStateOf(mm(initial.widthPt)) }
    var h by remember { mutableStateOf(mm(initial.heightPt)) }
    val size = w.toFloatOrNull()?.let { ww -> h.toFloatOrNull()?.let { hh -> PageSize(ww * PaperSizes.PT_PER_MM, hh * PaperSizes.PT_PER_MM) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = BestNoteShapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.paper_custom_title)) },
        text = {
            Row {
                OutlinedTextField(
                    w, { w = it }, label = { Text(stringResource(R.string.paper_width_mm)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.M))
                OutlinedTextField(
                    h, { h = it }, label = { Text(stringResource(R.string.paper_height_mm)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { size?.let { onConfirm(PaperSizes.clamp(it)) } }, enabled = size != null) { Text(stringResource(R.string.paper_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.insert_cancel)) } },
    )
}

/** A neutral page for previews when the notebook has none yet. */
internal fun previewPage(): Page = Page(NotebookId("preview"), 0)
