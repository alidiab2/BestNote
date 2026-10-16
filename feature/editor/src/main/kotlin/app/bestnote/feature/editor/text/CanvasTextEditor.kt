package app.bestnote.feature.editor.text

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.bestnote.core.model.ListNumbering
import app.bestnote.core.ui.keys.reportsTextEditing
import app.bestnote.engine.canvas.document.PagedDocumentView
import kotlin.math.roundToInt

/**
 * Types right on the page (TEXT-04): a text field laid exactly over the box being edited, at the page's zoom and
 * rotation, styled like the page draws it (paragraph styles, lists, quotes, code). The field brings the platform's
 * caret, selection handles, double-tap word selection, long-press menu and IME composition (Arabic, Gboard, Samsung
 * keyboard); mixed Arabic / English lines follow each paragraph's first strong letter, like the page.
 *
 * Coordinates are the canvas view's px: this must be drawn in a box that covers the canvas exactly.
 * [viewport] changes whenever the pages scroll or zoom; [visibleTop] / [visibleBottom] bound the part of the canvas
 * the keyboard, format bar and chrome leave, and the pages scroll so the caret stays inside it.
 */
@Composable
internal fun CanvasTextEditor(
    typing: TextTyping,
    view: PagedDocumentView,
    viewport: Int,
    visibleTop: Float,
    visibleBottom: Float,
    frameColor: Color,
) {
    val e = typing.editing ?: return
    val index = typing.pageIndex(e)
    // typing.version is read so every session change redraws the editor.
    val t = remember(viewport, index, view.width, view.height, typing.version) { view.pageTransform(index) } ?: return
    val s = t[0]
    val density = LocalDensity.current
    val draft = e.session.draft
    val box = draft.box
    val look = remember(box.copy(cx = 0f, cy = 0f, width = 1f, height = 1f), s, density) { textLook(box, s, density) }
    val measurer = rememberTextMeasurer()

    // New boxes grow with the words: as wide as the longest line, out to the page margin, then they wrap.
    if (e.isNew) {
        val pageW = view.pageSize(index)?.first ?: return
        val unwrapped = remember(look) {
            measurer.measure(look.annotated, look.style, constraints = Constraints()).size.width / s
        }
        val rtl = ListNumbering.isRtl(box.text)
        val minW = box.sizePt * TextTyping.MIN_WIDTH_EM
        val caretRoom = box.sizePt * CARET_ROOM_EM
        val (left, w) = AutoGrow.frame(e.anchorX, pageW, pageW * TextTyping.MARGIN, unwrapped + caretRoom, minW, rtl)
        typing.grow(left, w)
    }
    val b = e.session.draft.box
    val widthPx = b.width * s
    // The editor pivots on the box's top-center (a box grows downward from its top edge).
    val (tcx, tcy) = if (e.isNew) (b.cx to e.top) else TextHit.topCenter(b)
    val x = t[1] + tcx * s - widthPx / 2f
    val y = t[2] + tcy * s
    val rotation = Math.toDegrees(b.rotation.toDouble()).toFloat()

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(e) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    // A tap inside an existing box puts the caret where it landed, once the words are laid out.
    LaunchedEffect(layout, e) {
        val l = layout ?: return@LaunchedEffect
        val tap = e.tapLocal ?: return@LaunchedEffect
        e.tapLocal = null
        val offset = l.getOffsetForPosition(Offset(tap.first * s, tap.second * s))
        typing.onValue(typing.value.copy(selection = androidx.compose.ui.text.TextRange(offset)))
    }
    // Keep the caret line above the keyboard and the format bar (and below the top chrome).
    val caret = typing.value.selection.end
    LaunchedEffect(layout, caret, visibleTop, visibleBottom, viewport) {
        val l = layout ?: return@LaunchedEffect
        if (caret > l.layoutInput.text.length) return@LaunchedEffect
        val r = l.getCursorRect(caret)
        val dy = CaretViewport.scrollFor(y + r.top, y + r.bottom, visibleTop, visibleBottom, with(density) { CARET_MARGIN.toPx() })
        if (dy != 0) view.scrollByPx(0, dy)
    }

    val transformation = remember(look) {
        VisualTransformation { text ->
            TransformedText(if (text.text == look.annotated.text) look.annotated else text, OffsetMapping.Identity)
        }
    }
    val markerStyle = remember(look) { TextStyle(color = look.style.color, fontSize = look.style.fontSize, fontFamily = look.style.fontFamily) }
    val barColor = look.style.color.copy(alpha = QUOTE_ALPHA)
    val codeColor = look.style.color.copy(alpha = CODE_ALPHA)

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .graphicsLayer {
                rotationZ = rotation
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
    ) {
        BasicTextField(
            value = typing.value,
            onValueChange = typing::onValue,
            textStyle = look.style,
            cursorBrush = SolidColor(frameColor),
            visualTransformation = transformation,
            onTextLayout = { layout = it },
            modifier = Modifier
                .width(with(density) { widthPx.toDp() })
                .border(1.dp, frameColor.copy(alpha = FRAME_ALPHA))
                .focusRequester(focus)
                .reportsTextEditing()
                .semantics { contentDescription = "canvas-text-input" }
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        ev.isCtrlPressed && ev.key == Key.Z && !ev.isShiftPressed -> {
                            typing.act { it.undo() }
                            true
                        }
                        ev.isCtrlPressed && (ev.key == Key.Y || (ev.key == Key.Z && ev.isShiftPressed)) -> {
                            typing.act { it.redo() }
                            true
                        }
                        ev.key == Key.Backspace && typing.value.selection.collapsed && typing.value.selection.start == 0 &&
                            e.session.backspaceAtStart() -> {
                            typing.sync()
                            true
                        }
                        else -> false
                    }
                }
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    val n = l.layoutInput.text.length
                    for (p in look.decor) {
                        if (p.start > n) continue
                        val first = l.getLineForOffset(p.start)
                        val last = l.getLineForOffset(maxOf(p.start, p.end - 1).coerceAtMost(n))
                        val top = l.getLineTop(first)
                        val bottom = l.getLineBottom(last)
                        val rtl = l.getParagraphDirection(p.start.coerceAtMost(maxOf(0, n - 1))) == ResolvedTextDirection.Rtl
                        if (p.code) drawRect(codeColor, Offset(0f, top), Size(size.width, bottom - top))
                        if (p.quoteRoomPx > 0f) {
                            val bar = p.quoteRoomPx * QUOTE_BAR
                            val bx = if (!rtl) p.indentPx + bar else size.width - p.indentPx - 2 * bar
                            drawRect(barColor, Offset(bx, top), Size(bar, bottom - top))
                        }
                        val marker = p.marker ?: continue
                        val m = measurer.measure(marker, markerStyle)
                        val inner = p.indentPx + p.quoteRoomPx + p.markerRoomPx
                        val pad = p.markerRoomPx * MARKER_PAD
                        val mx = if (!rtl) inner - pad - m.size.width else size.width - inner + pad
                        drawText(m, topLeft = Offset(mx, l.getLineBaseline(first) - m.firstBaseline))
                    }
                },
        )
    }
}

private val CARET_MARGIN = 24.dp
private const val CARET_ROOM_EM = 0.6f
private const val FRAME_ALPHA = 0.45f
private const val QUOTE_ALPHA = 0.35f
private const val CODE_ALPHA = 0.08f
private const val MARKER_PAD = 0.2f
