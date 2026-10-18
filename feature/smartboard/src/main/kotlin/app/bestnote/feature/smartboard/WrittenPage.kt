package app.bestnote.feature.smartboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween // motion-ok: handwriting playback replays recorded pen time, not a UI transition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.engine.handwriting.WrittenStroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.PageSize
import app.bestnote.engine.canvas.document.ElementPainter
import app.bestnote.engine.handwriting.TypesetPage
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.paper.LineRole
import app.bestnote.engine.layout.paper.PaperGeometry

/** Natural writing time is replayed this many times faster: a full page writes itself in about 3 s. */
private const val SPEEDUP = 22f
private const val MIN_PLAY_MS = 1200
private const val MAX_PLAY_MS = 3600

/**
 * A ruled page that writes itself: [strokes] appear in their natural order and rhythm (left to
 * right, line by line), sped up so a full page takes about three seconds. [replayKey] restarts it.
 * A preview only — the inserted ink is drawn by the editor's own renderer.
 */
@Composable
internal fun WrittenPage(paper: RuledPaper, strokes: List<WrittenStroke>, durationMs: Long, replayKey: Any, modifier: Modifier = Modifier) {
    val colors = BestNoteTheme.colors.paper
    val paths = remember(strokes) { strokes.map(::fullPath) }
    val geometry = remember(paper) { PaperGeometry.of(paper.template, PageSize(paper.width, paper.height)) }
    val clock = remember(strokes) { Animatable(0f) }
    val playMs = (durationMs / SPEEDUP).toInt().coerceIn(MIN_PLAY_MS, MAX_PLAY_MS)
    LaunchedEffect(replayKey) {
        clock.snapTo(0f)
        clock.animateTo(1f, tween(playMs, easing = LinearEasing))
    }
    Canvas(
        modifier
            .aspectRatio(paper.width / paper.height)
            .shadow(6.dp)
            .semantics { contentDescription = "Handwritten page" },
    ) {
        val k = size.width / paper.width
        drawRect(colors.page)
        // The paper exactly as the editor prints it (PAGES-01 geometry).
        for (line in geometry.lines) {
            val color = if (line.role == LineRole.Margin || line.role == LineRole.Divider) colors.marginLine else colors.ruledLine
            drawLine(color, Offset(line.x0 * k, line.y0 * k), Offset(line.x1 * k, line.y1 * k), strokeWidth = if (line.role == LineRole.Margin) 1.5f else 1f)
        }
        geometry.dots?.let { d ->
            val r = (0.9f * k).coerceAtLeast(1f)
            for (j in 0 until d.rows) for (i in 0 until d.cols) drawCircle(colors.ruledLine, r, Offset((d.left + i * d.spacing) * k, (d.top + j * d.spacing) * k))
        }
        val now = clock.value * durationMs
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            for ((i, s) in strokes.withIndex()) {
                if (s.startMs > now) break
                val path = if (s.endMs <= now) paths[i] else partialPath(s, (now - s.startMs).toLong())
                ink(path, s)
            }
        }
    }
}

private fun DrawScope.ink(path: Path, s: WrittenStroke) {
    val brush = s.stroke.brush
    drawPath(path, Color(brush.colorArgb), style = Stroke(width = brush.size * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun fullPath(s: WrittenStroke): Path = partialPath(s, Long.MAX_VALUE)

private fun partialPath(s: WrittenStroke, untilMs: Long): Path {
    val samples = s.stroke.samples
    val p = Path()
    p.moveTo(samples[0].x, samples[0].y)
    if (samples.size == 1) {
        p.lineTo(samples[0].x + 0.3f, samples[0].y)
        return p
    }
    for (i in 1 until samples.size) {
        if (samples[i].timeMillis > untilMs) break
        p.lineTo(samples[i].x, samples[i].y)
    }
    return p
}

/**
 * A page typed with the handwriting fonts, drawn by the editor's own element painter (so the preview
 * is exactly what gets inserted), revealed piece by piece in about two seconds. [replayKey] restarts it.
 */
@Composable
internal fun TypedPage(
    paper: RuledPaper,
    page: TypesetPage,
    elements: List<PageElement>,
    replayKey: Any,
    modifier: Modifier = Modifier,
    images: app.bestnote.engine.canvas.document.ElementImages? = null,
) {
    val colors = BestNoteTheme.colors.paper
    val geometry = remember(paper) { PaperGeometry.of(paper.template, PageSize(paper.width, paper.height)) }
    val inkPaths = remember(page) { page.ink.map { s -> s to inkPath(s) } }
    val painter = remember { ElementPainter() }
    val clock = remember(page) { Animatable(0f) }
    LaunchedEffect(replayKey) {
        clock.snapTo(0f)
        clock.animateTo(1f, tween(TYPED_PLAY_MS, easing = LinearEasing))
    }
    Canvas(
        modifier
            .aspectRatio(paper.width / paper.height)
            .shadow(6.dp)
            .semantics { contentDescription = "Written page" },
    ) {
        val k = size.width / paper.width
        drawRect(colors.page)
        for (line in geometry.lines) {
            val color = if (line.role == LineRole.Margin || line.role == LineRole.Divider) colors.marginLine else colors.ruledLine
            drawLine(color, Offset(line.x0 * k, line.y0 * k), Offset(line.x1 * k, line.y1 * k), strokeWidth = if (line.role == LineRole.Margin) 1.5f else 1f)
        }
        geometry.dots?.let { d ->
            val r = (0.9f * k).coerceAtLeast(1f)
            for (j in 0 until d.rows) for (i in 0 until d.cols) drawCircle(colors.ruledLine, r, Offset((d.left + i * d.spacing) * k, (d.top + j * d.spacing) * k))
        }
        val shownInk = (clock.value * inkPaths.size).toInt().coerceAtMost(inkPaths.size)
        val shownText = (clock.value * 1.15f * elements.size).toInt().coerceAtMost(elements.size)
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            for ((s, path) in inkPaths.take(shownInk)) {
                // AI-12: a redrawn figure's dashed lines keep their dash.
                val dash = if (s.brush.dash == app.bestnote.core.model.LineDash.Dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(s.brush.size * 3.2f, s.brush.size * 2.6f)) else null
                drawPath(path, Color(s.brush.colorArgb), style = Stroke(width = s.brush.size * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash))
            }
            drawIntoCanvas { c -> painter.draw(c.nativeCanvas, elements.take(shownText), k, images, Runnable { }) }
        }
    }
}

private const val TYPED_PLAY_MS = 2200

private fun inkPath(s: app.bestnote.core.model.InkStroke): Path {
    val p = Path()
    val pts = s.samples
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) p.lineTo(pts[0].x + 0.3f, pts[0].y)
    for (i in 1 until pts.size) p.lineTo(pts[i].x, pts[i].y)
    return p
}
