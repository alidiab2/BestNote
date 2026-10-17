package app.bestnote.feature.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.CoverPalette
import app.bestnote.core.designsystem.theme.Elevation
import app.bestnote.core.designsystem.theme.InkPalette
import app.bestnote.core.designsystem.theme.isDarkChrome
import app.bestnote.core.designsystem.theme.legibleOn
import app.bestnote.core.designsystem.theme.softShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Cover size on the shelf (approved design: 124 × 168 dp). */
internal val CoverWidth: Dp = 124.dp
internal val CoverHeight: Dp = 168.dp

private const val SPINE_SHADE = 0.24f
private const val SPINE_HIGHLIGHT = 0.16f
private const val LABEL_WARMTH = 0.10f
private const val EDGE_LIGHTEN = 0.12f
private const val EDGE_LIGHTEN_DARK = 0.30f

internal fun coverColor(index: Int): Color = CoverPalette.All[index.mod(CoverPalette.All.size)]

/**
 * [coverColor] for small marks in the chrome (folder glyphs, folder squares): on dark chrome a navy
 * or black folder is lifted just enough to stay visible (>= 3:1), keeping its hue. Light theme: as is.
 */
@Composable
internal fun coverMarkColor(index: Int): Color {
    val scheme = MaterialTheme.colorScheme
    return if (isDarkChrome(scheme.surface)) coverColor(index).legibleOn(scheme.surfaceContainerHigh) else coverColor(index)
}

/**
 * A physical notebook cover: cover cloth in a [CoverPalette] color, a darker inset spine on the start
 * edge (so it mirrors with the chrome in RTL), and a cream paper label with the title in Fraunces.
 * The caller sizes it; [scale] shrinks the spine and label for small thumbnails.
 */
@Composable
internal fun NotebookCover(
    title: String,
    coverIndex: Int,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    shadow: Boolean = true,
    scale: Float = 1f,
    style: CoverStyle = CoverStyle.Plain,
) {
    val cover = coverColor(coverIndex)
    val paper = BestNoteTheme.colors.paper.page
    val edge = if (isDarkChrome(MaterialTheme.colorScheme.surface)) EDGE_LIGHTEN_DARK else EDGE_LIGHTEN
    val shape = BestNoteShapes.NotebookCover
    val spineWidth = 14.dp * scale
    Box(
        modifier
            .then(if (shadow) Modifier.softShadow(Elevation.High, shape) else Modifier)
            .clip(shape)
            .background(cover)
            .coverPattern(style, cover, paper, scale)
            // A faint edge, so dark covers (black, navy) still read on the graphite theme.
            .border(Elevation.Hairline, lerp(cover, paper, edge), shape),
    ) {
        // Inset spine: a darker band with a thin light crease where it meets the cover board.
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(spineWidth)
                .background(lerp(cover, MaterialTheme.colorScheme.scrim, SPINE_SHADE)),
        )
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = spineWidth).fillMaxHeight().width(1.dp)
                .background(lerp(cover, paper, SPINE_HIGHLIGHT)),
        )
        if (showLabel) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp * scale, start = spineWidth + 10.dp * scale, end = 10.dp * scale)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(3.dp))
                    .background(lerp(paper, CoverPalette.Mustard, LABEL_WARMTH))
                    .padding(horizontal = 6.dp * scale, vertical = 8.dp * scale),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 13.sp * scale,
                        lineHeight = 16.sp * scale,
                    ),
                    color = InkPalette.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A miniature page: paper, ruled lines, a margin line and a few lines of "handwriting". Stands in for
 * the real page thumbnail until DOC-10's thumbnail cache is on main. Paper never follows the theme.
 */
@Composable
internal fun PageThumbnail(modifier: Modifier = Modifier, seed: Int = 0) {
    val paper = BestNoteTheme.colors.paper
    val shape = RoundedCornerShape(4.dp)
    Canvas(modifier.softShadow(Elevation.Low, shape).clip(shape).background(paper.page)) {
        val gap = size.height / RULED_LINES
        for (i in 2 until RULED_LINES) {
            drawLine(paper.ruledLine, Offset(0f, gap * i), Offset(size.width, gap * i), strokeWidth = 1f)
        }
        val margin = size.width * MARGIN_AT
        drawLine(paper.marginLine, Offset(margin, 0f), Offset(margin, size.height), strokeWidth = 1f)
        val ink = Stroke(width = size.width / INK_WIDTH_DIVISOR, cap = StrokeCap.Round)
        val lines = 5 + seed.mod(3)
        for (i in 0 until lines) {
            val baseline = gap * (i + 2) - gap * 0.25f
            val end = size.width * (0.55f + ((seed + i * 7).mod(5)) * 0.08f)
            val path = Path().apply {
                moveTo(margin + gap * 0.4f, baseline)
                var x = margin + gap * 0.4f
                var up = true
                while (x < end) {
                    val step = gap * 0.35f
                    quadraticTo(x + step / 2, baseline + (if (up) -gap * 0.45f else gap * 0.05f), x + step, baseline)
                    x += step
                    up = !up
                }
            }
            drawPath(path, if (i == 0) InkPalette.Blue else InkPalette.Black, style = ink)
        }
    }
}

private const val RULED_LINES = 12
private const val MARGIN_AT = 0.16f
private const val INK_WIDTH_DIVISOR = 70f

/**
 * The cached page thumbnail at [path] (DOC-10), decoded off the main thread, or [PageThumbnail] while
 * it loads, when there is none, or when it cannot be decoded. [version] re-decodes after an edit.
 */
@Composable
internal fun NotebookThumbnail(path: String?, version: Long, modifier: Modifier = Modifier, seed: Int = 0) {
    val bitmap by produceState<ImageBitmap?>(null, path, version) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() }
        }
    }
    val image = bitmap
    if (image == null) {
        PageThumbnail(modifier, seed)
    } else {
        val shape = RoundedCornerShape(4.dp)
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = modifier.softShadow(Elevation.Low, shape).clip(shape).background(BestNoteTheme.colors.paper.page),
        )
    }
}
