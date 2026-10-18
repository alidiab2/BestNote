package app.bestnote.feature.templates

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.fonts.FontRegistry
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement
import app.bestnote.engine.canvas.document.ElementPainter
import app.bestnote.engine.handwriting.NoteLayout
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.handwriting.TypeRun
import app.bestnote.engine.handwriting.TypesetPage
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.paper.LineRole
import app.bestnote.engine.layout.paper.PaperGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** [TextMeasure] with the registered handwriting typefaces, exactly as the page renderer draws them. */
internal class PaintMeasure : TextMeasure {
    private val paints = HashMap<HandFont, Paint>()
    private val cache = HashMap<Pair<String, HandFont>, Float>()

    private fun paint(f: HandFont): Paint = paints.getOrPut(f) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = HandwritingTypefaces.of(f.id) ?: Typeface.DEFAULT
            textSize = REF
        }
    }

    override fun width(text: String, font: HandFont, sizePt: Float): Float =
        cache.getOrPut(text to font) { paint(font).measureText(text) / REF } * sizePt

    override fun ascent(font: HandFont): Float = -paint(font).fontMetrics.ascent / REF
    override fun descent(font: HandFont): Float = paint(font).fontMetrics.descent / REF

    private companion object {
        const val REF = 100f
    }
}

private fun elementOf(r: TypeRun, m: TextMeasure): PageElement {
    val pad = r.sizePt * 0.12f
    val width = r.width + 2 * pad
    val ascent = m.ascent(r.font) * r.sizePt
    val height = (m.ascent(r.font) + m.descent(r.font)) * r.sizePt * 1.12f
    return TextElement(
        id = UUID.randomUUID().toString(),
        cx = r.left - pad + width / 2,
        cy = r.baseline - ascent + height / 2,
        width = width,
        height = height,
        text = r.text,
        sizePt = r.sizePt.coerceIn(TextElement.MIN_SIZE_PT, TextElement.MAX_SIZE_PT),
        colorArgb = r.color,
        align = TextAlignment.Start,
        font = r.font.id,
    )
}

/** A laid-out preview page: the paper, text boxes and pen strokes. */
internal class PreviewPage(val paper: RuledPaper, val elements: List<PageElement>, val ink: List<InkStroke>)

internal fun buildPreview(template: NoteTemplate, sample: NoteDocument = NoteSamples.forTemplate(template)): PreviewPage {
    val measure = PaintMeasure()
    val (paper, page: TypesetPage) = NoteLayout.previewPage(template, measure, sample, PageSize(595f, 842f))
    return PreviewPage(paper, page.runs.map { elementOf(it, measure) }, page.ink)
}

/**
 * LIVE preview of [template]: its first page of the sample document (written in the template's notes
 * language), laid out by the same engine that writes real pages. Recomputed off the main thread whenever
 * the template or the font set changes.
 */
@Composable
internal fun TemplatePreview(template: NoteTemplate, modifier: Modifier = Modifier) {
    val registry = FontRegistry.get(LocalContext.current)
    val fonts by registry.fonts.collectAsState()
    var page by remember { mutableStateOf<PreviewPage?>(null) }
    LaunchedEffect(template, fonts) {
        page = withContext(Dispatchers.Default) { runCatching { buildPreview(template) }.getOrNull() }
    }
    val p = page
    val colors = BestNoteTheme.colors.paper
    val painter = remember { ElementPainter() }
    val aspect = (p?.paper?.let { it.width / it.height }) ?: (595f / 842f)
    // wrapContentSize drops the caller's minimum size so the page always fits inside BOTH the width and the
    // height it is given (a fixed width alone made a tall page overflow onto the controls above it).
    Canvas(modifier.wrapContentSize(Alignment.TopCenter).aspectRatio(aspect).shadow(6.dp).semantics { contentDescription = "Template preview" }) {
        drawRect(colors.page)
        p ?: return@Canvas
        val k = size.width / p.paper.width
        val geometry = PaperGeometry.of(p.paper.template, PageSize(p.paper.width, p.paper.height))
        for (line in geometry.lines) {
            val c = if (line.role == LineRole.Margin || line.role == LineRole.Divider) colors.marginLine else colors.ruledLine
            drawLine(c, Offset(line.x0 * k, line.y0 * k), Offset(line.x1 * k, line.y1 * k), strokeWidth = if (line.role == LineRole.Margin) 1.5f else 1f)
        }
        geometry.dots?.let { d ->
            val r = (0.9f * k).coerceAtLeast(1f)
            for (j in 0 until d.rows) for (i in 0 until d.cols) drawCircle(colors.ruledLine, r, Offset((d.left + i * d.spacing) * k, (d.top + j * d.spacing) * k))
        }
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            for (s in p.ink) {
                val pts = s.samples
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    if (pts.size == 1) lineTo(pts[0].x + 0.3f, pts[0].y)
                    for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                }
                drawPath(path, Color(s.brush.colorArgb), style = Stroke(width = s.brush.size * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawIntoCanvas { c -> painter.draw(c.nativeCanvas, p.elements, k, null, Runnable { }) }
        }
    }
}
