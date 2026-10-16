package app.bestnote.feature.smartboard

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import app.bestnote.ai.vision.AiProviderKind
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bestnote.core.designsystem.theme.BestNoteShapes
import app.bestnote.core.designsystem.theme.BestNoteTheme
import app.bestnote.core.designsystem.theme.Spacing
import app.bestnote.core.model.RectPt
import kotlin.math.min
import kotlin.math.roundToInt

/** AI-04/AI-07: capture a board region, read it, review the page writing itself, insert it. */
@Composable
fun SmartBoardScreen(onClose: () -> Unit, onInsert: (SmartBoardResult) -> Unit, viewModel: SmartBoardViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var keyDialog by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(state, onClose, onKey = { keyDialog = true }, onProvider = viewModel::switchProvider, onModel = viewModel::switchModel)
            AnimatedContent(state.stage, contentKey = { it::class }, label = "stage", modifier = Modifier.weight(1f).fillMaxWidth()) { stage ->
                when (stage) {
                    SmartBoardStage.Capture -> CaptureStage(state, viewModel)
                    is SmartBoardStage.Reading -> ReadingStage(stage.step, demo = state.photo == null || !state.hasKey)
                    is SmartBoardStage.Review -> ReviewStage(stage, viewModel, onInsert)
                    is SmartBoardStage.Failed -> FailedStage(stage, onRetry = viewModel::read, onBack = viewModel::retake)
                }
            }
        }
    }
    if (keyDialog) KeyDialog(state.provider, state.keyHint, onSave = { viewModel.saveKey(it); keyDialog = false }, onDismiss = { keyDialog = false })
}

@Composable
private fun TopBar(
    state: SmartBoardUiState,
    onClose: () -> Unit,
    onKey: () -> Unit,
    onProvider: (AiProviderKind) -> Unit,
    onModel: (String) -> Unit,
) {
    var switcher by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.smartboard_close)) }
        Icon(Icons.Rounded.AutoAwesome, null, tint = BestNoteTheme.colors.aiAccent, modifier = Modifier.padding(start = Spacing.XS).size(20.dp))
        Text(stringResource(R.string.smartboard_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Spacing.S).weight(1f))
        // Quick switcher: provider + model, right where the key is shown.
        Box {
            TextButton(onClick = { switcher = true }) {
                Text(
                    providerName(state.provider) + (if (state.modelLabel.isNotBlank()) " · " + state.modelLabel else ""),
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 260.dp),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.ExpandMore, stringResource(R.string.smartboard_switch_model), Modifier.size(18.dp))
            }
            DropdownMenu(expanded = switcher, onDismissRequest = { switcher = false }) {
                Text(
                    stringResource(R.string.smartboard_switch_provider),
                    style = MaterialTheme.typography.labelMedium,
                    color = BestNoteTheme.colors.muted,
                    modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.XS),
                )
                FlowRow(
                    Modifier.padding(horizontal = Spacing.M).widthIn(max = 360.dp),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.S),
                ) {
                    state.switcher.providers.forEach { (kind, configured) ->
                        FilterChip(
                            selected = kind == state.provider,
                            onClick = { onProvider(kind) },
                            label = { Text(providerName(kind)) },
                            leadingIcon = if (!configured) ({ Icon(Icons.Rounded.Key, null, Modifier.size(14.dp)) }) else null,
                        )
                    }
                }
                Text(
                    stringResource(R.string.smartboard_switch_model),
                    style = MaterialTheme.typography.labelMedium,
                    color = BestNoteTheme.colors.muted,
                    modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.XS),
                )
                state.switcher.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label, maxLines = 1) },
                        onClick = {
                            onModel(option.model)
                            switcher = false
                        },
                        leadingIcon = {
                            when {
                                option.selected -> Icon(Icons.Rounded.CheckCircle, null, tint = BestNoteTheme.colors.aiAccent, modifier = Modifier.size(18.dp))
                                option.favorite -> Icon(Icons.Rounded.Star, null, modifier = Modifier.size(18.dp))
                                else -> Spacer(Modifier.size(18.dp))
                            }
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(state.keyHint?.let { stringResource(R.string.smartboard_key_set, it) } ?: stringResource(R.string.smartboard_key_none)) },
                    onClick = {
                        switcher = false
                        onKey()
                    },
                    leadingIcon = { Icon(Icons.Rounded.Key, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun providerName(kind: AiProviderKind) = stringResource(
    when (kind) {
        AiProviderKind.Gemini -> R.string.smartboard_provider_gemini
        AiProviderKind.Claude -> R.string.smartboard_provider_claude
        AiProviderKind.OpenAi -> R.string.smartboard_provider_openai
        AiProviderKind.OpenAiCompatible -> R.string.smartboard_provider_compatible_short
    },
)

// ---------------------------------------------------------------- 1 · capture

@Composable
private fun CaptureStage(state: SmartBoardUiState, viewModel: SmartBoardViewModel) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::onPhoto) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(viewModel::onPhoto) }
    val photo = state.photo
    if (photo == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.XL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.L, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.smartboard_capture_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.smartboard_capture_body),
                style = MaterialTheme.typography.bodyLarge,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                Button(onClick = {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".smartboard.files", viewModel.cameraFile())
                    cameraUri = uri
                    runCatching { camera.launch(uri) }
                }) { IconLabel(Icons.Rounded.PhotoCamera, stringResource(R.string.smartboard_take_photo)) }
                FilledTonalButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    IconLabel(Icons.Rounded.PhotoLibrary, stringResource(R.string.smartboard_choose_photo))
                }
            }
            OutlinedButton(onClick = viewModel::read) { IconLabel(Icons.Rounded.AutoAwesome, stringResource(R.string.smartboard_try_sample)) }
            ArabicToggle(state.arabic, viewModel::setArabic)
            PrivacyNote(state.hasKey)
        }
    } else {
        Column(Modifier.fillMaxSize().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            Text(stringResource(R.string.smartboard_crop_hint), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
            CropView(photo, state.crop, viewModel::setCrop, Modifier.weight(1f).fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                OutlinedButton(onClick = viewModel::retake) { IconLabel(Icons.Rounded.PhotoCamera, stringResource(R.string.smartboard_retake)) }
                ArabicToggle(state.arabic, viewModel::setArabic)
                Spacer(Modifier.weight(1f))
                if (!state.hasKey) DemoChip()
                Button(onClick = viewModel::read) { IconLabel(Icons.Rounded.AutoAwesome, stringResource(R.string.smartboard_write_it)) }
            }
        }
    }
}

@Composable
private fun IconLabel(icon: ImageVector, label: String) {
    Icon(icon, null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(Spacing.S))
    Text(label)
}

@Composable
private fun ArabicToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Switch(checked = on, onCheckedChange = onChange)
        Text(stringResource(R.string.smartboard_arabic_board), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DemoChip() {
    Surface(shape = BestNoteShapes.Pill, color = BestNoteTheme.colors.warning.copy(alpha = 0.16f)) {
        Text(
            stringResource(R.string.smartboard_demo_chip),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.M, vertical = Spacing.XS + Spacing.XXS),
        )
    }
}

@Composable
private fun PrivacyNote(hasKey: Boolean) {
    Row(Modifier.widthIn(max = 560.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Icon(Icons.Rounded.Lock, null, tint = BestNoteTheme.colors.success, modifier = Modifier.size(20.dp))
        Text(
            stringResource(if (hasKey) R.string.smartboard_privacy else R.string.smartboard_privacy_demo),
            style = MaterialTheme.typography.bodySmall,
            color = BestNoteTheme.colors.muted,
        )
    }
}

/** The photo with a draggable crop frame: drag a corner to resize, drag inside to move. */
@Composable
private fun CropView(photo: Bitmap, crop: RectPt, onCrop: (RectPt) -> Unit, modifier: Modifier = Modifier) {
    val image = remember(photo) { photo.asImageBitmap() }
    val current by rememberUpdatedState(crop)
    val handleTouch = with(LocalDensity.current) { 40.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        modifier.pointerInput(photo) {
            var handle = -1
            detectDragGestures(
                onDragStart = { at ->
                    val fit = fit(Size(size.width.toFloat(), size.height.toFloat()), image.width, image.height)
                    val c = current
                    val corners = listOf(Offset(c.left, c.top), Offset(c.right, c.top), Offset(c.right, c.bottom), Offset(c.left, c.bottom))
                        .map { Offset(fit.left + it.x * fit.width, fit.top + it.y * fit.height) }
                    handle = corners.indices.minByOrNull { (corners[it] - at).getDistance() }?.takeIf { (corners[it] - at).getDistance() < handleTouch } ?: 4
                },
                onDrag = { change, delta ->
                    change.consume()
                    val fit = fit(Size(size.width.toFloat(), size.height.toFloat()), image.width, image.height)
                    val dx = delta.x / fit.width
                    val dy = delta.y / fit.height
                    val c = current
                    onCrop(
                        when (handle) {
                            0 -> RectPt((c.left + dx).coerceIn(0f, c.right - MIN_CROP), (c.top + dy).coerceIn(0f, c.bottom - MIN_CROP), c.right, c.bottom)
                            1 -> RectPt(c.left, (c.top + dy).coerceIn(0f, c.bottom - MIN_CROP), (c.right + dx).coerceIn(c.left + MIN_CROP, 1f), c.bottom)
                            2 -> RectPt(c.left, c.top, (c.right + dx).coerceIn(c.left + MIN_CROP, 1f), (c.bottom + dy).coerceIn(c.top + MIN_CROP, 1f))
                            3 -> RectPt((c.left + dx).coerceIn(0f, c.right - MIN_CROP), c.top, c.right, (c.bottom + dy).coerceIn(c.top + MIN_CROP, 1f))
                            else -> {
                                val mx = dx.coerceIn(-c.left, 1f - c.right)
                                val my = dy.coerceIn(-c.top, 1f - c.bottom)
                                RectPt(c.left + mx, c.top + my, c.right + mx, c.bottom + my)
                            }
                        },
                    )
                },
            )
        },
    ) {
        val fit = fit(size, image.width, image.height)
        drawImage(
            image,
            dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
        )
        val r = Rect(fit.left + crop.left * fit.width, fit.top + crop.top * fit.height, fit.left + crop.right * fit.width, fit.top + crop.bottom * fit.height)
        val dim = Color.Black.copy(alpha = 0.45f)
        drawRect(dim, fit.topLeft, Size(fit.width, r.top - fit.top))
        drawRect(dim, Offset(fit.left, r.bottom), Size(fit.width, fit.bottom - r.bottom))
        drawRect(dim, Offset(fit.left, r.top), Size(r.left - fit.left, r.height))
        drawRect(dim, Offset(r.right, r.top), Size(fit.right - r.right, r.height))
        drawRect(accent, r.topLeft, r.size, style = Stroke(2.dp.toPx()))
        for (corner in listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)) {
            drawCircle(Color.White, 9.dp.toPx(), corner)
            drawCircle(accent, 9.dp.toPx(), corner, style = Stroke(3.dp.toPx()))
        }
    }
}

private const val MIN_CROP = 0.08f

/** Where an image of [w]x[h] lands when fitted (centered, aspect kept) into [box]. */
private fun fit(box: Size, w: Int, h: Int): Rect {
    val s = min(box.width / w, box.height / h)
    val dw = w * s
    val dh = h * s
    return Rect(Offset((box.width - dw) / 2, (box.height - dh) / 2), Size(dw, dh))
}

// ---------------------------------------------------------------- 2 · reading

@Composable
private fun ReadingStage(step: Int, demo: Boolean) {
    val steps = listOf(
        R.string.smartboard_step_crop,
        R.string.smartboard_step_read,
        R.string.smartboard_step_order,
        R.string.smartboard_step_ruler,
        R.string.smartboard_step_colors,
        R.string.smartboard_step_write,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            Text(stringResource(R.string.smartboard_reading_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(if (demo) R.string.smartboard_reading_demo else R.string.smartboard_reading_key),
                style = MaterialTheme.typography.bodyMedium,
                color = BestNoteTheme.colors.muted,
            )
            steps.forEachIndexed { i, label ->
                val done = i < step
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    Icon(
                        if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        null,
                        tint = if (done || i == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        stringResource(label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (i <= step) MaterialTheme.colorScheme.onSurface else BestNoteTheme.colors.muted,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 3 · review

@Composable
private fun ReviewStage(review: SmartBoardStage.Review, viewModel: SmartBoardViewModel, onInsert: (SmartBoardResult) -> Unit) {
    val ink = review.ink
    var page by remember(ink) { mutableIntStateOf(0) }
    var replay by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(Spacing.L)) {
        val wide = maxWidth >= 760.dp
        val preview: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WrittenPage(ink.paper, ink.pages.pages[page], ink.pages.durationMs(page), replayKey = page to replay)
                }
                if (ink.pages.pages.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Icon(Icons.Rounded.ChevronLeft, null) }
                        Text("${page + 1} / ${ink.pages.pages.size}", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { page = (page + 1).coerceAtMost(ink.pages.pages.lastIndex) }, enabled = page < ink.pages.pages.lastIndex) {
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
            }
        }
        val panel: @Composable (Modifier) -> Unit = { m ->
            ReviewPanel(review, onReplay = { replay++ }, onRetake = viewModel::retake, onInsert = { viewModel.result()?.let(onInsert) }, modifier = m)
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XL)) {
                preview(Modifier.weight(1.5f).fillMaxHeight())
                panel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                preview(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ReviewPanel(review: SmartBoardStage.Review, onReplay: () -> Unit, onRetake: () -> Unit, onInsert: () -> Unit, modifier: Modifier = Modifier) {
    val ink = review.ink
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
        Text(stringResource(R.string.smartboard_review_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.smartboard_review_stats, ink.pages.pages.size, ink.pages.strokeCount, review.tookMs / 1000f),
            style = MaterialTheme.typography.bodyMedium,
            color = BestNoteTheme.colors.muted,
        )
        if (review.demo) DemoChip()
        if (ink.dropped > 0) Text(stringResource(R.string.smartboard_review_dropped, ink.dropped), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.warning)
        listOf(
            R.string.smartboard_did_order,
            R.string.smartboard_did_lines,
            R.string.smartboard_did_ruler,
            R.string.smartboard_did_box,
            R.string.smartboard_did_margin,
        ).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Icon(Icons.Rounded.CheckCircle, null, tint = BestNoteTheme.colors.success, modifier = Modifier.size(18.dp))
                Text(stringResource(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(Spacing.S))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            OutlinedButton(onClick = onReplay) { IconLabel(Icons.Rounded.Replay, stringResource(R.string.smartboard_replay)) }
            OutlinedButton(onClick = onRetake) { IconLabel(Icons.Rounded.PhotoCamera, stringResource(R.string.smartboard_retake)) }
        }
        Button(onClick = onInsert, modifier = Modifier.fillMaxWidth()) {
            IconLabel(Icons.AutoMirrored.Rounded.NoteAdd, stringResource(R.string.smartboard_insert))
        }
    }
}

// ---------------------------------------------------------------- failure

@Composable
private fun FailedStage(failed: SmartBoardStage.Failed, onRetry: () -> Unit, onBack: () -> Unit) {
    val message = when (failed.reason) {
        SmartBoardStage.Reason.NoBlocks -> R.string.smartboard_error_empty
        SmartBoardStage.Reason.InvalidKey -> R.string.smartboard_error_key
        SmartBoardStage.Reason.RateLimited -> R.string.smartboard_error_rate
        SmartBoardStage.Reason.QuotaExhausted -> R.string.smartboard_error_quota
        SmartBoardStage.Reason.Network -> R.string.smartboard_error_network
        SmartBoardStage.Reason.ModelUnavailable -> R.string.smartboard_error_model
        SmartBoardStage.Reason.ServerBusy -> R.string.smartboard_error_busy
        SmartBoardStage.Reason.Blocked -> R.string.smartboard_error_blocked
        SmartBoardStage.Reason.Truncated -> R.string.smartboard_error_truncated
        SmartBoardStage.Reason.Other -> R.string.smartboard_error_other
    }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(Spacing.L), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.M),
        ) {
            Text(stringResource(message), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            // Debug builds / verbose logging only: what actually went wrong.
            failed.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted, textAlign = TextAlign.Center)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.smartboard_retake)) }
                Button(onClick = onRetry) { Text(stringResource(R.string.smartboard_retry)) }
            }
            failed.diagnostics?.let { dump ->
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(dump))
                    copied = true
                }) { Text(stringResource(if (copied) R.string.smartboard_copied_raw else R.string.smartboard_copy_raw)) }
            }
        }
    }
}

@Composable
private fun KeyDialog(provider: AiProviderKind, hint: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    val name = stringResource(
        when (provider) {
            AiProviderKind.Gemini -> R.string.smartboard_provider_gemini
            AiProviderKind.Claude -> R.string.smartboard_provider_claude
            AiProviderKind.OpenAi -> R.string.smartboard_provider_openai
            AiProviderKind.OpenAiCompatible -> R.string.smartboard_provider_compatible
        },
    )
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.smartboard_key_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                Text(stringResource(R.string.smartboard_key_body, name), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text(hint ?: "…") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }, enabled = key.isNotBlank()) { Text(stringResource(R.string.smartboard_key_save)) } },
        dismissButton = {
            Row {
                if (hint != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.smartboard_key_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.smartboard_cancel)) }
            }
        },
    )
}
