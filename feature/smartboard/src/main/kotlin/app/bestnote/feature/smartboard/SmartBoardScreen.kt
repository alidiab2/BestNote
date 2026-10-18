package app.bestnote.feature.smartboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.bestnote.ai.scan.Pt
import app.bestnote.ai.scan.Quad
import app.bestnote.core.model.EquationStyle
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.Decoration
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.TemplateChange
import app.bestnote.engine.layout.StyleTemplates
import app.bestnote.ai.vision.providers.WritingMode
import app.bestnote.ai.vision.NotesLanguage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.res.pluralStringResource
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
import app.bestnote.core.designsystem.theme.isDarkChrome
import kotlin.math.min
import kotlin.math.roundToInt

/** AI-04/AI-07: capture a board region, read it, review the page writing itself, insert it. */
@Composable
fun SmartBoardScreen(onClose: () -> Unit, onInsert: (SmartBoardResult) -> Unit, onManageTemplates: () -> Unit = {}, viewModel: SmartBoardViewModel = hiltViewModel()) {
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refreshTemplates()
        onPauseOrDispose { }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalManageTemplates provides onManageTemplates) {
        SmartBoardContent(onClose, onInsert, viewModel)
    }
}

private val LocalManageTemplates = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
private fun SmartBoardContent(onClose: () -> Unit, onInsert: (SmartBoardResult) -> Unit, viewModel: SmartBoardViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var keyDialog by remember { mutableStateOf(false) }
    var switcher by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopBar(state, onClose, switcher, { switcher = it }, onKey = { keyDialog = true }, onProvider = viewModel::switchProvider, onModel = viewModel::switchModel)
            AnimatedContent(state.stage, contentKey = { it::class }, label = "stage", modifier = Modifier.weight(1f).fillMaxWidth()) { stage ->
                when (stage) {
                    SmartBoardStage.Capture -> CaptureStage(state, viewModel)
                    is SmartBoardStage.Reading -> ReadingStage(stage, demo = state.photos.isEmpty() || !state.hasKey, provider = providerName(state.provider), onCancel = viewModel::cancel)
                    is SmartBoardStage.Review -> ReviewStage(stage, state, viewModel, onInsert)
                    is SmartBoardStage.Failed -> FailedStage(stage, onRetry = viewModel::read, onBack = viewModel::backToPhotos, onKey = { keyDialog = true }, onPickModel = { switcher = true })
                }
            }
        }
    }
    state.textOnlyWarning?.let { name ->
        AlertDialog(
            onDismissRequest = viewModel::dismissTextOnlyWarning,
            title = { Text(stringResource(R.string.smartboard_text_only_title)) },
            text = { Text(stringResource(R.string.smartboard_text_only_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissTextOnlyWarning()
                    switcher = true
                }) { Text(stringResource(R.string.smartboard_pick_model)) }
            },
            dismissButton = { TextButton(onClick = viewModel::readAnyway) { Text(stringResource(R.string.smartboard_try_anyway)) } },
        )
    }
    state.viewer?.let { PhotoViewer(it, state.secure, viewModel::closeViewer) }
    if (keyDialog) KeyDialog(state.provider, state.keyHint, onSave = { viewModel.saveKey(it); keyDialog = false }, onDismiss = { keyDialog = false })
}

@Composable
private fun TopBar(
    state: SmartBoardUiState,
    onClose: () -> Unit,
    switcher: Boolean,
    onSwitcher: (Boolean) -> Unit,
    onKey: () -> Unit,
    onProvider: (AiProviderKind) -> Unit,
    onModel: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.smartboard_close)) }
        Icon(Icons.Rounded.AutoAwesome, null, tint = BestNoteTheme.colors.aiAccent, modifier = Modifier.padding(start = Spacing.XS).size(20.dp))
        Text(stringResource(R.string.smartboard_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Spacing.S).weight(1f))
        // Quick switcher: provider + model, right where the key is shown.
        Box {
            TextButton(onClick = { onSwitcher(true) }) {
                Text(
                    providerName(state.provider) + (if (state.modelLabel.isNotBlank()) " · " + state.modelLabel else ""),
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 260.dp),
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.ExpandMore, stringResource(R.string.smartboard_switch_model), Modifier.size(18.dp))
            }
            DropdownMenu(expanded = switcher, onDismissRequest = { onSwitcher(false) }) {
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
                        text = {
                            Text(
                                option.label + if (option.textOnly) " · " + stringResource(R.string.smartboard_no_images_badge) else "",
                                maxLines = 1,
                                color = if (option.textOnly) BestNoteTheme.colors.muted else androidx.compose.ui.graphics.Color.Unspecified,
                            )
                        },
                        onClick = {
                            onModel(option.model)
                            onSwitcher(false)
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
                        onSwitcher(false)
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
    val room = SmartBoardViewModel.MAX_PHOTOS - state.photos.size
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(SmartBoardViewModel.MAX_PHOTOS)) { uris -> viewModel.onPhotos(uris) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::onPhoto) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let(viewModel::onPhoto) }
    val takePhoto = {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".smartboard.files", viewModel.cameraFile())
        cameraUri = uri
        runCatching { camera.launch(uri) }
        Unit
    }
    val choose = {
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (room > 1) pickMany.launch(request) else pickOne.launch(request)
    }
    val photo = state.photos.getOrNull(state.current)
    if (photo == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.XL),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.L, Alignment.CenterVertically),
        ) {
            Text(stringResource(if (state.followUp) R.string.smartboard_continue_title else R.string.smartboard_capture_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(if (state.followUp) R.string.smartboard_continue_body else R.string.smartboard_capture_body),
                style = MaterialTheme.typography.bodyLarge,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            TemplateChips(state, viewModel::setTemplate, Modifier.widthIn(max = 640.dp), viewModel::setEquationStyle)
            if (state.loading > 0) {
                CircularProgressIndicator()
                Text(stringResource(R.string.smartboard_finding_edges), style = MaterialTheme.typography.bodyMedium, color = BestNoteTheme.colors.muted)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
                    Button(onClick = takePhoto) { IconLabel(Icons.Rounded.PhotoCamera, stringResource(R.string.smartboard_take_photo)) }
                    FilledTonalButton(onClick = choose) { IconLabel(Icons.Rounded.PhotoLibrary, stringResource(R.string.smartboard_choose_photo)) }
                }
                if (state.followUp && state.canRefinePage) {
                    // AI-13: change the page (template, font, "make headings red"…) without a new photo.
                    OutlinedButton(onClick = viewModel::openForRefine) { IconLabel(Icons.Rounded.AutoAwesome, stringResource(R.string.smartboard_refine_page)) }
                    if (state.storedPhotos > 0) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.S, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
                            OutlinedButton(onClick = viewModel::viewPhotos) { IconLabel(Icons.Rounded.PhotoLibrary, pluralStringResource(R.plurals.smartboard_photos_view_n, state.storedPhotos, state.storedPhotos)) }
                            OutlinedButton(onClick = { viewModel.regenerate(Regenerate.ThisPage) }) { IconLabel(Icons.Rounded.Replay, stringResource(R.string.smartboard_regenerate_page)) }
                            OutlinedButton(onClick = { viewModel.regenerate(Regenerate.NewPage) }) { IconLabel(Icons.Rounded.Replay, stringResource(R.string.smartboard_regenerate_new)) }
                        }
                    }
                } else {
                    OutlinedButton(onClick = viewModel::read) { IconLabel(Icons.Rounded.AutoAwesome, stringResource(R.string.smartboard_try_sample)) }
                }
            }
            LanguagePicker(state.language, viewModel::setLanguage)
            PrivacyNote(state.hasKey)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Spacing.L, vertical = Spacing.S), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            EdgeChip(photo.detected)
            Text(
                stringResource(if (state.flatPreview != null) R.string.smartboard_preview_hint else R.string.smartboard_crop_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = BestNoteTheme.colors.muted,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.resetQuad(whole = !photo.detected || photo.quad != Quad.full(0f)) }) {
                Text(stringResource(if (photo.quad == Quad.full(0f)) R.string.smartboard_find_edges else R.string.smartboard_whole_photo))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val flat = state.flatPreview
            if (flat != null) {
                val image = remember(flat) { flat.asImageBitmap() }
                androidx.compose.foundation.Image(image, stringResource(R.string.smartboard_preview), Modifier.fillMaxSize())
            } else {
                QuadCropView(photo, viewModel::setQuad, Modifier.fillMaxSize())
            }
        }
        PhotoStrip(state, onSelect = viewModel::select, onRemove = viewModel::removePhoto, onCamera = takePhoto, onGallery = choose)
        TemplateChips(state, viewModel::setTemplate, onEquations = viewModel::setEquationStyle)
        FlowRow(
            verticalArrangement = Arrangement.Center,
            horizontalArrangement = Arrangement.spacedBy(Spacing.M),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledSwitch(state.enhance, viewModel::setEnhance, stringResource(R.string.smartboard_enhance))
            LabeledSwitch(state.flatPreview != null, viewModel::showPreview, stringResource(R.string.smartboard_preview))
            LanguagePicker(state.language, viewModel::setLanguage)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.M)) {
            OutlinedButton(onClick = viewModel::retake) { IconLabel(Icons.Rounded.Close, stringResource(R.string.smartboard_start_over)) }
            Spacer(Modifier.weight(1f))
            if (!state.hasKey) DemoChip()
            Button(onClick = viewModel::read, enabled = state.loading == 0) {
                IconLabel(Icons.Rounded.AutoAwesome, if (state.photos.size > 1) stringResource(R.string.smartboard_write_n, state.photos.size) else stringResource(R.string.smartboard_write_it))
            }
        }
    }
}

@Composable
private fun EdgeChip(found: Boolean) {
    Surface(shape = BestNoteShapes.Pill, color = chipTint(if (found) BestNoteTheme.colors.success else BestNoteTheme.colors.warning)) {
        Row(Modifier.padding(horizontal = Spacing.M, vertical = Spacing.XS), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (found) Icons.Rounded.CheckCircle else Icons.Rounded.CropFree, null, Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.XS))
            Text(stringResource(if (found) R.string.smartboard_board_found else R.string.smartboard_board_not_found), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun LabeledSwitch(on: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Switch(checked = on, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Thumbnails of the board's photos (reading order), the one being adjusted outlined; "+" adds more. */
@Composable
private fun PhotoStrip(state: SmartBoardUiState, onSelect: (Int) -> Unit, onRemove: (Int) -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        state.photos.forEachIndexed { i, p ->
            val thumb = remember(p.bitmap) { p.bitmap.asImageBitmap() }
            Box {
                androidx.compose.foundation.Image(
                    thumb,
                    stringResource(R.string.smartboard_photo_n, i + 1),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(72.dp, 54.dp)
                        .clip(BestNoteShapes.Control)
                        .border(if (i == state.current) 3.dp else 1.dp, if (i == state.current) accent else MaterialTheme.colorScheme.outline, BestNoteShapes.Control)
                        .clickable { onSelect(i) },
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clickable { onRemove(i) },
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.smartboard_remove_photo), Modifier.padding(3.dp)) }
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(horizontal = 5.dp),
                )
            }
        }
        if (state.loading > 0) CircularProgressIndicator(Modifier.size(28.dp))
        if (state.photos.size < SmartBoardViewModel.MAX_PHOTOS) {
            FilledTonalButton(onClick = onCamera) { IconLabel(Icons.Rounded.PhotoCamera, stringResource(R.string.smartboard_add_photo)) }
            FilledTonalButton(onClick = onGallery) { IconLabel(Icons.Rounded.PhotoLibrary, stringResource(R.string.smartboard_add_from_gallery)) }
        }
    }
}

@Composable
private fun IconLabel(icon: ImageVector, label: String) {
    Icon(icon, null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(Spacing.S))
    Text(label)
}

/** The language's own name, so every user finds theirs. */
private fun endonym(l: NotesLanguage): String? = when (l) {
    NotesLanguage.Board -> null
    NotesLanguage.English -> "English"
    NotesLanguage.Hebrew -> "עברית"
    NotesLanguage.Arabic -> "العربية"
    NotesLanguage.French -> "Français"
    NotesLanguage.Spanish -> "Español"
    NotesLanguage.German -> "Deutsch"
    NotesLanguage.Russian -> "Русский"
    NotesLanguage.Turkish -> "Türkçe"
}

/** "Notes in: Same as the board ▾" — or a language the notes are translated into (maths untouched). */
@Composable
private fun LanguagePicker(language: NotesLanguage, onLanguage: (NotesLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Translate, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.XS))
            Text(stringResource(R.string.smartboard_notes_in, endonym(language) ?: stringResource(R.string.smartboard_language_board)))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            NotesLanguage.entries.forEach { l ->
                DropdownMenuItem(
                    text = { Text(endonym(l) ?: stringResource(R.string.smartboard_language_board)) },
                    onClick = {
                        open = false
                        onLanguage(l)
                    },
                    leadingIcon = { if (l == language) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp)) },
                )
            }
        }
    }
}

/**
 * A soft status-chip fill: 16% of [color] in light. On dark chrome a light role (success) gets a
 * stronger tint and an already-dark container role (warning) is used as is, so the chip still reads.
 */
@Composable
private fun chipTint(color: Color): Color = when {
    !isDarkChrome(MaterialTheme.colorScheme.surface) -> color.copy(alpha = CHIP_TINT)
    isDarkChrome(color) -> color
    else -> color.copy(alpha = CHIP_TINT_DARK)
}

private const val CHIP_TINT = 0.16f
private const val CHIP_TINT_DARK = 0.24f

@Composable
private fun DemoChip() {
    Surface(shape = BestNoteShapes.Pill, color = chipTint(BestNoteTheme.colors.warning)) {
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

/**
 * The photo with the board's outline: four corner handles that each drag on their own (so a board
 * shot at an angle is outlined exactly), the outside dimmed. A loupe-free design: handles are big
 * and the outline follows the finger.
 */
@Composable
private fun QuadCropView(photo: BoardPhoto, onQuad: (Quad) -> Unit, modifier: Modifier = Modifier) {
    val image = remember(photo.bitmap) { photo.bitmap.asImageBitmap() }
    val current by rememberUpdatedState(photo.quad)
    val handleTouch = with(LocalDensity.current) { 44.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.smartboard_crop_hint)
    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(photo.id) {
                var handle = -1
                detectDragGestures(
                    onDragStart = { at ->
                        val fit = fit(Size(size.width.toFloat(), size.height.toFloat()), image.width, image.height)
                        val corners = current.corners.map { Offset(fit.left + it.x * fit.width, fit.top + it.y * fit.height) }
                        handle = corners.indices.minByOrNull { (corners[it] - at).getDistance() }?.takeIf { (corners[it] - at).getDistance() < handleTouch * 1.6f } ?: -1
                    },
                    onDragEnd = { handle = -1 },
                    onDrag = { change, delta ->
                        if (handle < 0) return@detectDragGestures
                        change.consume()
                        val fit = fit(Size(size.width.toFloat(), size.height.toFloat()), image.width, image.height)
                        val c = current.corners[handle]
                        val moved = Pt((c.x + delta.x / fit.width).coerceIn(0f, 1f), (c.y + delta.y / fit.height).coerceIn(0f, 1f))
                        val next = current.with(handle, moved)
                        // Never let the outline fold over itself.
                        if (next.isConvex && next.area > MIN_AREA) onQuad(next)
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
        val pts = photo.quad.corners.map { Offset(fit.left + it.x * fit.width, fit.top + it.y * fit.height) }
        val outline = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        val outside = Path().apply {
            addRect(fit)
            addPath(outline)
            fillType = PathFillType.EvenOdd
        }
        drawPath(outside, Color.Black.copy(alpha = 0.45f))
        drawPath(outline, accent.copy(alpha = 0.10f))
        drawPath(outline, accent, style = Stroke(2.5.dp.toPx()))
        for (corner in pts) {
            drawCircle(Color.White, 11.dp.toPx(), corner)
            drawCircle(accent, 11.dp.toPx(), corner, style = Stroke(3.dp.toPx()))
        }
    }
}

private const val MIN_AREA = 0.02f

/** Where an image of [w]x[h] lands when fitted (centered, aspect kept) into [box]. */
private fun fit(box: Size, w: Int, h: Int): Rect {
    val s = min(box.width / w, box.height / h)
    val dw = w * s
    val dh = h * s
    return Rect(Offset((box.width - dw) / 2, (box.height - dh) / 2), Size(dw, dh))
}

// ---------------------------------------------------------------- 2 · reading

@Composable
private fun ReadingStage(reading: SmartBoardStage.Reading, demo: Boolean, provider: String, onCancel: () -> Unit) {
    val step = reading.step
    val steps = listOf(
        R.string.smartboard_step_crop,
        R.string.smartboard_step_read,
        R.string.smartboard_step_order,
        R.string.smartboard_step_ruler,
        R.string.smartboard_step_colors,
        R.string.smartboard_step_write,
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 440.dp).padding(Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
            Text(stringResource(R.string.smartboard_reading_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                if (demo) stringResource(R.string.smartboard_reading_demo) else stringResource(R.string.smartboard_reading_key, provider),
                style = MaterialTheme.typography.bodyMedium,
                color = BestNoteTheme.colors.muted,
            )
            if (reading.photos > 1) {
                Text(stringResource(R.string.smartboard_reading_photo, reading.photo, reading.photos), style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(
                    progress = { (reading.photo - 1 + step / (steps.size - 1f)) / reading.photos },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
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
            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.smartboard_cancel)) }
        }
    }
}

// ---------------------------------------------------------------- 3 · review

@Composable
private fun ReviewStage(review: SmartBoardStage.Review, state: SmartBoardUiState, viewModel: SmartBoardViewModel, onInsert: (SmartBoardResult) -> Unit) {
    val pages = review.pageCount
    var page by remember(review.text, review.ink) { mutableIntStateOf(0) }
    var replay by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(Spacing.L), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val wide = maxWidth >= 760.dp
        val preview: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val p = page.coerceIn(0, pages - 1)
                    val text = review.text
                    if (text != null) {
                        val typed = text.pages.pages[p]
                        val elements = remember(typed) { viewModel.previewElements(typed) }
                        TypedPage(text.paper, typed, elements, replayKey = Triple(p, replay, typed), images = viewModel.previewImages)
                    } else {
                        val ink = review.ink!!
                        WrittenPage(ink.paper, ink.pages.pages[p], ink.pages.durationMs(p), replayKey = Triple(p, replay, ink))
                    }
                }
                if (pages > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { page = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Icon(Icons.Rounded.ChevronLeft, null) }
                        Text("${page + 1} / $pages", style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = { page = (page + 1).coerceAtMost(pages - 1) }, enabled = page < pages - 1) {
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
            }
        }
        val panel: @Composable (Modifier) -> Unit = { m ->
            ReviewPanel(
                review,
                state = state,
                afterPage = viewModel.afterPage,
                onTemplate = viewModel::setTemplate,
                onMode = viewModel::setMode,
                onLanguage = viewModel::setLanguage,
                onGlossary = viewModel::insertGlossary,
                onReplay = { replay++ },
                onRetake = viewModel::backToPhotos,
                onInsert = { fresh -> viewModel.result(fresh)?.let(onInsert) },
                modifier = m,
            )
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.XL)) {
                preview(Modifier.weight(1.3f).fillMaxHeight())
                panel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.M)) {
                preview(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth().heightIn(max = 360.dp))
            }
        }
    }
    // AI-13: "Ask Smart Board…" docked under the result.
    RefineBar(state, viewModel)
    }
}

@Composable
private fun styleName(style: StyleTemplate) = stringResource(
    when (style.id) {
        StyleTemplates.Minimal.id -> R.string.smartboard_style_minimal
        StyleTemplates.Colorful.id -> R.string.smartboard_style_colorful
        StyleTemplates.Cornell.id -> R.string.smartboard_style_cornell
        StyleTemplates.CheatSheet.id -> R.string.smartboard_style_cheatsheet
        StyleTemplates.StudyColumns.id -> R.string.smartboard_style_study
        else -> R.string.smartboard_style_classic
    },
)

@Composable
private fun roleName(role: NoteRole) = stringResource(
    when (role) {
        NoteRole.Title -> R.string.smartboard_role_title
        NoteRole.Heading, NoteRole.Subheading -> R.string.smartboard_role_heading
        NoteRole.Definition -> R.string.smartboard_role_definition
        NoteRole.Theorem -> R.string.smartboard_role_theorem
        NoteRole.Formula, NoteRole.Result -> R.string.smartboard_role_result
        NoteRole.Example -> R.string.smartboard_role_example
        NoteRole.Step -> R.string.smartboard_role_step
        NoteRole.Note, NoteRole.Warning -> R.string.smartboard_role_warning
        NoteRole.Margin -> R.string.smartboard_role_margin
        NoteRole.Body -> R.string.smartboard_role_body
    },
)

/** A small sketch of a page in [style]: title, heading with its decoration, body lines, a boxed result, a margin note. */
/**
 * The note templates as compact chips, each with its colour dots; chosen BEFORE capture (and
 * switchable on the review screen). Locked on a follow-up: the page keeps its template.
 */
@Composable
private fun TemplateChips(state: SmartBoardUiState, onTemplate: (NoteTemplate) -> Unit, modifier: Modifier = Modifier, onEquations: ((EquationStyle?) -> Unit)? = null) {
    val pen = MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.S), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.smartboard_template), style = MaterialTheme.typography.labelLarge)
        state.templates.forEach { t ->
            FilterChip(
                selected = t.id == state.noteTemplate.id,
                onClick = { onTemplate(t) },
                enabled = !state.followUp || state.refineOnly || t.id == state.noteTemplate.id,
                label = { Text(if (t.builtIn) styleName(t.look) else t.name, maxLines = 1) },
                leadingIcon = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(NoteRole.Title, NoteRole.Definition, NoteRole.Result).forEach { r ->
                            Box(Modifier.size(7.dp).background(t.look.role(r).color?.let { Color(it) } ?: pen, CircleShape))
                        }
                    }
                },
            )
        }
        if (onEquations != null) {
            val own = state.equationStyle ?: state.noteTemplate.look.equationStyle
            Text(stringResource(R.string.smartboard_equations), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Spacing.S))
            FilterChip(own == EquationStyle.Handwritten, { onEquations(EquationStyle.Handwritten) }, label = { Text(stringResource(R.string.smartboard_eq_handwritten)) })
            FilterChip(own == EquationStyle.Typeset, { onEquations(EquationStyle.Typeset) }, label = { Text(stringResource(R.string.smartboard_eq_typeset)) })
        }
        val manage = LocalManageTemplates.current
        androidx.compose.material3.AssistChip(onClick = manage, label = { Text(stringResource(R.string.smartboard_manage_templates)) })
    }
}

/** Which colour means what in the chosen style. */
@Composable
private fun ColorLegend(style: StyleTemplate) {
    val pen = MaterialTheme.colorScheme.onSurface
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.M), verticalArrangement = Arrangement.spacedBy(Spacing.XXS)) {
        style.legend.forEach { (role, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color?.let { Color(it) } ?: pen, CircleShape))
                Spacer(Modifier.width(Spacing.XS))
                Text(roleName(role), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ReviewPanel(
    review: SmartBoardStage.Review,
    state: SmartBoardUiState,
    afterPage: Int,
    onTemplate: (NoteTemplate) -> Unit,
    onMode: (WritingMode) -> Unit,
    onLanguage: (NotesLanguage) -> Unit,
    onGlossary: (Boolean) -> Unit,
    onReplay: () -> Unit,
    onRetake: () -> Unit,
    onInsert: (newNotebook: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        Text(stringResource(R.string.smartboard_review_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.smartboard_review_stats_pages, review.pageCount, review.tookMs / 1000f),
            style = MaterialTheme.typography.bodyMedium,
            color = BestNoteTheme.colors.muted,
        )
        if (review.demo) DemoChip()
        if (review.dropped > 0) Text(stringResource(R.string.smartboard_review_dropped, review.dropped), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.warning)
        // AI-12: redrawn drawings, and the ones only kept as a picture (flagged).
        val figures = review.notes.flatten().mapNotNull { it.figure }
        val redrawn = figures.count { it.source == app.bestnote.engine.layout.FigureSource.Redrawn || it.source == app.bestnote.engine.layout.FigureSource.FromData }
        val pictures = figures.count { it.source == app.bestnote.engine.layout.FigureSource.Picture }
        if (redrawn > 0) Text(stringResource(R.string.smartboard_review_drawings, redrawn), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted)
        if (pictures > 0) Text(stringResource(R.string.smartboard_review_pictures, pictures), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.warning)
        LanguagePicker(state.language, onLanguage)
        TemplateChips(state, onTemplate)
        state.notice?.let { change ->
            val msg = when (change) {
                TemplateChange.LookOnly -> R.string.smartboard_notice_look
                TemplateChange.NeedsAi -> R.string.smartboard_notice_ai
                TemplateChange.Same -> null
            }
            msg?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = BestNoteTheme.colors.muted) }
        }
        ColorLegend(state.style)
        if (state.glossarySize > 0 || review.notes.terms.isNotEmpty()) {
            FilterChip(
                selected = state.fullGlossary,
                onClick = { onGlossary(!state.fullGlossary) },
                label = { Text(stringResource(R.string.smartboard_insert_glossary, state.glossarySize)) },
            )
        }
        Text(stringResource(R.string.smartboard_writing), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            FilterChip(selected = state.mode == WritingMode.Font, onClick = { onMode(WritingMode.Font) }, label = { Text(stringResource(R.string.smartboard_mode_font)) })
            FilterChip(selected = state.mode == WritingMode.Strokes, onClick = { onMode(WritingMode.Strokes) }, label = { Text(stringResource(R.string.smartboard_mode_strokes)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.S)) {
            OutlinedButton(onClick = onReplay) { IconLabel(Icons.Rounded.Replay, stringResource(R.string.smartboard_replay)) }
            OutlinedButton(onClick = onRetake) { IconLabel(Icons.Rounded.PhotoCamera, stringResource(R.string.smartboard_back_to_photos)) }
        }
        Button(onClick = { onInsert(false) }, modifier = Modifier.fillMaxWidth()) {
            IconLabel(
                Icons.AutoMirrored.Rounded.NoteAdd,
                when {
                    state.followUp -> stringResource(R.string.smartboard_update_page)
                    afterPage > 0 -> stringResource(R.string.smartboard_insert_after, afterPage)
                    else -> stringResource(R.string.smartboard_insert)
                },
            )
        }
        OutlinedButton(onClick = { onInsert(true) }, modifier = Modifier.fillMaxWidth()) {
            IconLabel(Icons.Rounded.LibraryAdd, stringResource(R.string.smartboard_insert_new_notebook))
        }
    }
}

// ---------------------------------------------------------------- failure

@Composable
private fun FailedStage(failed: SmartBoardStage.Failed, onRetry: () -> Unit, onBack: () -> Unit, onKey: () -> Unit, onPickModel: () -> Unit) {
    val message = when (failed.reason) {
        SmartBoardStage.Reason.NoBlocks -> R.string.smartboard_error_empty
        SmartBoardStage.Reason.MissingKey -> R.string.smartboard_error_no_key
        SmartBoardStage.Reason.InvalidKey -> R.string.smartboard_error_key
        SmartBoardStage.Reason.RateLimited -> R.string.smartboard_error_rate
        SmartBoardStage.Reason.QuotaExhausted -> R.string.smartboard_error_quota
        SmartBoardStage.Reason.Network -> R.string.smartboard_error_network
        SmartBoardStage.Reason.ModelUnavailable -> R.string.smartboard_error_model
        SmartBoardStage.Reason.ServerBusy -> R.string.smartboard_error_busy
        SmartBoardStage.Reason.Blocked -> R.string.smartboard_error_blocked
        SmartBoardStage.Reason.Truncated -> R.string.smartboard_error_truncated
        SmartBoardStage.Reason.ImageNotSeen -> R.string.smartboard_error_no_image
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
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.smartboard_back_to_photos)) }
                if (failed.reason == SmartBoardStage.Reason.InvalidKey || failed.reason == SmartBoardStage.Reason.MissingKey) {
                    Button(onClick = onKey) { Text(stringResource(R.string.smartboard_set_key)) }
                } else if (failed.reason == SmartBoardStage.Reason.ImageNotSeen) {
                    Button(onClick = onPickModel) { Text(stringResource(R.string.smartboard_pick_model)) }
                } else {
                    Button(onClick = onRetry) { Text(stringResource(R.string.smartboard_retry)) }
                }
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
