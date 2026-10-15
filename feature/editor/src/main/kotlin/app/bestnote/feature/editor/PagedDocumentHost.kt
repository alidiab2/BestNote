package app.bestnote.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.Motion
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.designsystem.theme.softShadow
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.canvas.EraserMode
import app.bestnote.engine.canvas.document.PagedDocument
import app.bestnote.engine.canvas.document.PagedDocumentView
import app.bestnote.core.designsystem.R as DesignR

/**
 * How fingers behave on the page (SPEN-02, CANVAS-04), from settings. [penSeen] is persisted the
 * first time a pen touches or hovers, which turns [autoStylusOnly] into stylus-only for good.
 */
data class FingerInput(
    val stylusOnly: Boolean = false,
    val autoStylusOnly: Boolean = true,
    val penSeen: Boolean = false,
    val twoFingerTapUndo: Boolean = true,
    val threeFingerTapRedo: Boolean = true,
) {
    companion object {
        /** Before settings are known: stylus-first, a finger never inks. */
        val BeforeSettings = FingerInput(stylusOnly = true)
    }
}

/**
 * The notebook surface (CANVAS-07): real pages on the Atelier desk, scrolling vertically, with a
 * "3 / 12" page chip. [onView] hands the view to the screen for toolbar actions.
 */
@Composable
internal fun PagedDocumentHost(
    document: PagedDocument,
    tool: Tool,
    penStyle: PenStyle,
    colorArgb: Int,
    brushSize: Float,
    eraserMode: EraserMode,
    input: FingerInput,
    quickPalette: Boolean,
    doubleClickUndo: Boolean,
    onView: (PagedDocumentView) -> Unit,
    onIndicator: (page: Int, count: Int) -> Unit = { _, _ -> },
    onIndicatorClick: (() -> Unit)? = null,
    onPenSeen: () -> Unit = {},
    topInsetPx: Int = 0,
    bottomInsetPx: Int = 0,
    modifier: Modifier = Modifier,
) {
    val penSeenCallback by rememberUpdatedState(onPenSeen)
    var current by remember { mutableIntStateOf(-1) }
    var count by remember { mutableIntStateOf(0) }
    val muted = BestNoteTheme.colors.muted.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    Box(modifier) {
        AndroidView(
            factory = { context ->
                PagedDocumentView(context).also { view ->
                    view.addCardTypeface = runCatching { ResourcesCompat.getFont(context, DesignR.font.instrument_sans) }.getOrNull()
                    view.revealDurationMs = Motion.Duration.Long
                    view.zoomDurationMs = Motion.Duration.Standard
                    view.pageIndicatorListener = PagedDocumentView.PageIndicatorListener { page, total ->
                        current = page
                        count = total
                        onIndicator(page, total)
                    }
                    view.document = document
                    view.penSeenListener = { penSeenCallback() }
                    onView(view)
                }
            },
            update = { view ->
                view.tool = tool
                view.penStyle = penStyle
                view.colorArgb = colorArgb
                view.brushSize = brushSize
                view.eraserMode = eraserMode
                view.quickPaletteEnabled = quickPalette
                view.hoverDoubleClickUndo = doubleClickUndo
                view.stylusOnly = input.stylusOnly
                view.autoStylusOnly = input.autoStylusOnly
                view.penSeen = input.penSeen
                view.twoFingerTapUndo = input.twoFingerTapUndo
                view.threeFingerTapRedo = input.threeFingerTapRedo
                view.setContentInsets(topInsetPx, bottomInsetPx)
                view.addCardColor = muted
                view.selectionColor = accent
                view.document = document
            },
            onRelease = { it.document = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (count > 0 && current >= 0) {
            PageIndicator(current + 1, count, Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.XL), onIndicatorClick)
        }
    }
}

/** Small "3 / 12" pill. Static: it never animates, so it can't distract under the pen. */
@Composable
internal fun PageIndicator(page: Int, count: Int, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = modifier
            .softShadow(Elevation.Low, BestNoteShapes.Pill)
            .semantics { contentDescription = "Page $page of $count" },
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = BestNoteShapes.Pill,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            "$page / $count",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.M, vertical = Spacing.XS + Spacing.XXS),
        )
    }
}
