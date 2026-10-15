package app.bestnote.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing

/** A button under an empty state. */
internal class EmptyAction(val label: String, val onClick: () -> Unit, val primary: Boolean = true)

/** Which drawing heads an empty state. */
internal enum class EmptyArt { Shelf, Folder, Star, Bin, Search, Filter }

/** Centered empty state: an illustration on a soft disc, a Fraunces title, a hint and actions. */
@Composable
internal fun EmptyState(art: EmptyArt, title: String, body: String, modifier: Modifier = Modifier, actions: List<EmptyAction> = emptyList()) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Spacing.XXL + Spacing.L),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(196.dp).clip(BestNoteShapes.Pill).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) { EmptyIllustration(art) }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.XL),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = BestNoteTheme.colors.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp).padding(top = Spacing.S),
        )
        if (actions.isNotEmpty()) {
            Row(Modifier.padding(top = Spacing.XL), horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                actions.forEach { a ->
                    if (a.primary) {
                        PillButton(a.label, a.onClick, icon = Icons.Outlined.Add)
                    } else {
                        PillButton(
                            a.label,
                            a.onClick,
                            background = MaterialTheme.colorScheme.surface,
                            foreground = MaterialTheme.colorScheme.onSurface,
                            outlined = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyIllustration(art: EmptyArt) {
    val muted = BestNoteTheme.colors.muted
    val paper = BestNoteTheme.colors.paper
    when (art) {
        EmptyArt.Shelf -> Box(Modifier.size(150.dp, 120.dp)) {
            // Three notebooks leaning on each other.
            NotebookCover("", 3, Modifier.align(Alignment.Center).offset(x = 34.dp, y = 4.dp).rotate(12f).size(58.dp, 80.dp), showLabel = false, scale = 0.45f, style = CoverStyle.Dots)
            NotebookCover("", 2, Modifier.align(Alignment.Center).offset(x = (-30).dp, y = 4.dp).rotate(-10f).size(58.dp, 80.dp), showLabel = false, scale = 0.45f)
            NotebookCover("", 0, Modifier.align(Alignment.Center).size(64.dp, 88.dp), scale = 0.5f, showLabel = false, style = CoverStyle.Stripes)
        }
        EmptyArt.Folder -> Canvas(Modifier.size(120.dp, 96.dp)) {
            val tab = Size(size.width * 0.42f, size.height * 0.2f)
            drawRoundRect(muted.copy(alpha = 0.55f), Offset(0f, 0f), tab, CornerRadius(8.dp.toPx()))
            drawRoundRect(paper.page, Offset(size.width * 0.12f, size.height * 0.08f), Size(size.width * 0.76f, size.height * 0.6f), CornerRadius(4.dp.toPx()))
            drawRoundRect(muted.copy(alpha = 0.8f), Offset(0f, size.height * 0.16f), Size(size.width, size.height * 0.84f), CornerRadius(10.dp.toPx()))
        }
        EmptyArt.Star -> Canvas(Modifier.size(96.dp)) {
            val path = starPath(size.minDimension / 2, center)
            drawPath(path, paper.marginLine.copy(alpha = 0.9f))
            drawPath(path, muted, style = Stroke(width = 2.dp.toPx()))
        }
        EmptyArt.Bin -> Canvas(Modifier.size(84.dp, 100.dp)) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(muted, Offset(0f, size.height * 0.16f), Offset(size.width, size.height * 0.16f), stroke.width, StrokeCap.Round)
            drawRoundRect(muted, Offset(size.width * 0.36f, 0f), Size(size.width * 0.28f, size.height * 0.1f), CornerRadius(4.dp.toPx()), style = stroke)
            drawRoundRect(muted, Offset(size.width * 0.1f, size.height * 0.24f), Size(size.width * 0.8f, size.height * 0.76f), CornerRadius(8.dp.toPx()), style = stroke)
            for (i in 1..3) {
                val x = size.width * (0.1f + 0.2f * i)
                drawLine(muted.copy(alpha = 0.6f), Offset(x, size.height * 0.36f), Offset(x, size.height * 0.88f), stroke.width * 0.7f, StrokeCap.Round)
            }
        }
        EmptyArt.Search, EmptyArt.Filter -> Canvas(Modifier.size(110.dp)) {
            // A page with a magnifier over it.
            drawRoundRect(paper.page, Offset(size.width * 0.12f, size.height * 0.04f), Size(size.width * 0.58f, size.height * 0.78f), CornerRadius(4.dp.toPx()))
            for (i in 1..5) {
                val y = size.height * (0.04f + 0.13f * i)
                drawLine(paper.ruledLine, Offset(size.width * 0.18f, y), Offset(size.width * 0.64f, y), 1.5.dp.toPx())
            }
            val c = Offset(size.width * 0.58f, size.height * 0.56f)
            val r = size.width * 0.2f
            drawCircle(muted, r, c, style = Stroke(width = 4.dp.toPx()))
            drawLine(muted, c + Offset(r * 0.72f, r * 0.72f), c + Offset(r * 1.6f, r * 1.6f), 6.dp.toPx(), StrokeCap.Round)
            if (art == EmptyArt.Filter) {
                drawLine(muted, c + Offset(-r * 0.5f, 0f), c + Offset(r * 0.5f, 0f), 3.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

private fun starPath(radius: Float, center: Offset): androidx.compose.ui.graphics.Path {
    val path = androidx.compose.ui.graphics.Path()
    val inner = radius * STAR_INNER
    for (i in 0 until STAR_POINTS * 2) {
        val r = if (i % 2 == 0) radius else inner
        val a = Math.PI / STAR_POINTS * i - Math.PI / 2
        val p = Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    return path
}

private const val STAR_POINTS = 5
private const val STAR_INNER = 0.45f

/** The empty state for [kind], with the actions that help most there. */
@Composable
internal fun NotebooksEmpty(kind: EmptyKind, query: String, folderName: String?, callbacks: LibraryCallbacks) {
    val newNotebook = EmptyAction(stringResource(R.string.library_new_notebook), callbacks.newNotebook)
    val quicknote = EmptyAction(stringResource(R.string.library_quicknote_title), callbacks.quicknote, primary = false)
    when (kind) {
        EmptyKind.NoNotebooks -> EmptyState(
            EmptyArt.Shelf,
            stringResource(R.string.library_empty_title),
            stringResource(R.string.library_empty_body),
            actions = listOf(newNotebook, quicknote),
        )
        EmptyKind.EmptyFolder -> EmptyState(
            EmptyArt.Folder,
            stringResource(R.string.library_empty_folder_title, folderName.orEmpty()),
            stringResource(R.string.library_empty_folder_body),
            actions = listOf(EmptyAction(stringResource(R.string.library_new_notebook_here), callbacks.newNotebook)),
        )
        EmptyKind.NoFavorites -> EmptyState(EmptyArt.Star, stringResource(R.string.library_empty_favorites_title), stringResource(R.string.library_empty_favorites_body))
        EmptyKind.NoQuicknotes -> EmptyState(
            EmptyArt.Shelf,
            stringResource(R.string.library_empty_quicknotes_title),
            stringResource(R.string.library_empty_quicknotes_body),
            actions = listOf(EmptyAction(stringResource(R.string.library_quicknote_title), callbacks.quicknote)),
        )
        EmptyKind.NoRecent -> EmptyState(EmptyArt.Shelf, stringResource(R.string.library_empty_recent_title), stringResource(R.string.library_empty_recent_body))
        EmptyKind.NoPdfs -> EmptyState(
            EmptyArt.Filter,
            stringResource(R.string.library_empty_pdfs_title),
            stringResource(R.string.library_empty_pdfs_body),
            actions = listOf(EmptyAction(stringResource(R.string.library_clear_filters), callbacks.clearFilters, primary = false)),
        )
        EmptyKind.NoMatches -> EmptyState(
            EmptyArt.Filter,
            stringResource(R.string.library_empty_filter_title),
            stringResource(R.string.library_empty_filter_body),
            actions = listOf(EmptyAction(stringResource(R.string.library_clear_filters), callbacks.clearFilters, primary = false)),
        )
        EmptyKind.NoSearchResults -> EmptyState(
            EmptyArt.Search,
            stringResource(R.string.library_empty_search_title, query.trim()),
            stringResource(R.string.library_empty_search_body),
            actions = listOf(EmptyAction(stringResource(R.string.library_clear_search), { callbacks.query("") }, primary = false)),
        )
    }
}
