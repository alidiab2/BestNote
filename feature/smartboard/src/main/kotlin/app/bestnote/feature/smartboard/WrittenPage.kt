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
import app.bestnote.engine.layout.RuledPaper

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
        for (i in 0 until paper.lineCount) {
            val y = paper.lineY(i) * k
            drawLine(colors.ruledLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        drawLine(colors.marginLine, Offset(paper.marginX * k, 0f), Offset(paper.marginX * k, size.height), strokeWidth = 1.5f)
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
