package app.bestnote.feature.smartboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.bestnote.ai.scan.Quad
import app.bestnote.ai.vision.LegacyNotes
import app.bestnote.ai.vision.NoteWire
import app.bestnote.ai.vision.SmartBoardGroup
import app.bestnote.ai.vision.SmartBoardRecord
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.NotebookStore
import app.bestnote.engine.handwriting.NotePages
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.ReservedLines
import app.bestnote.engine.layout.TemplateChange
import app.bestnote.ai.vision.NotesLanguage
import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.ai.vision.Redactor
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.ai.vision.providers.SmartBoardStyles
import app.bestnote.ai.vision.providers.WritingMode
import app.bestnote.core.designsystem.font.HandwritingTypefaces
import app.bestnote.core.model.PageElement
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.RuledPaper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max

/** What Smart Board shows: capture → reading → the handwritten page. */
sealed interface SmartBoardStage {
    /** Choosing a photo, then the region of the board to read. */
    data object Capture : SmartBoardStage

    /** [step] of [SmartBoardViewModel.STEPS] is in progress, on photo [photo] (1-based) of [photos]. */
    data class Reading(val step: Int, val photo: Int = 1, val photos: Int = 1) : SmartBoardStage

    /**
     * The written page: [text] (handwriting fonts, the default) or [ink] (synthetic strokes). The AI's
     * notes stay cached (in [TemplateNotes]) so another look or writing mode re-draws them without a
     * new AI call; [notes] is what the page shows (normalised: maths out of RTL, numbers, glossary).
     */
    data class Review(val ink: BoardInk?, val text: NotePages?, val demo: Boolean, val tookMs: Long, val notes: NoteDocument) : SmartBoardStage {
        val paper: RuledPaper get() = text?.paper ?: ink!!.paper
        val pageCount: Int get() = text?.pages?.pages?.size ?: ink!!.pages.pages.size
        val dropped: Int get() = ink?.dropped ?: 0
        val dropReasons: List<String> get() = ink?.dropReasons.orEmpty()
    }

    /** [diagnostics]: provider, error, drop reasons and raw answer, for "Copy raw AI response" (debug / verbose only). */
    data class Failed(val reason: Reason, val detail: String? = null, val diagnostics: String? = null) : SmartBoardStage

    enum class Reason { NoBlocks, MissingKey, InvalidKey, RateLimited, QuotaExhausted, Network, ModelUnavailable, ServerBusy, Blocked, Truncated, Other }
}

/** One photo of the board and the outline to read on it ([quad], normalised; [detected] when found automatically). */
data class BoardPhoto(val id: Long, val bitmap: Bitmap, val quad: Quad, val detected: Boolean)

data class SmartBoardUiState(
    val stage: SmartBoardStage = SmartBoardStage.Capture,
    /** The photos of the board, in reading order (downscaled for the screen and the provider). */
    val photos: List<BoardPhoto> = emptyList(),
    /** The photo whose corners are being adjusted. */
    val current: Int = 0,
    /** Flatten-and-clean preview of the current photo, while "Preview" is on. */
    val flatPreview: Bitmap? = null,
    /** Auto-enhance: even lighting, white board, crisp ink. */
    val enhance: Boolean = true,
    /** Photos still being decoded and searched for the board's edges. */
    val loading: Int = 0,
    /** Paper the notes are written on: the template's own, else the notebook page's (never asked). */
    val template: PaperTemplate = PaperTemplate.Ruled,
    /** The note template, chosen BEFORE capture (saved per notebook): its look, AI instructions and elements. */
    val noteTemplate: NoteTemplate = NoteTemplates.byId(null),
    val templates: List<NoteTemplate> = NoteTemplates.builtIns(),
    /** What the last template switch on the review screen did (re-drawn, or read again). */
    val notice: TemplateChange? = null,
    /** Continue / add photo on an existing Smart Board page: the notes are merged into that page. */
    val followUp: Boolean = false,
    /** Terms in the notebook glossary (the "Insert glossary" action shows when there are any). */
    val glossarySize: Int = 0,
    /** The page's Glossary element lists the whole notebook glossary (inserted on demand). */
    val fullGlossary: Boolean = false,
    val mode: WritingMode = WritingMode.Font,
    val hasKey: Boolean = false,
    val keyHint: String? = null,
    /** The provider Settings selected (Gemini, Claude, OpenAI, compatible). */
    val provider: AiProviderKind = AiProviderKind.Gemini,
    /** The model that will be called, and how the header shows it. */
    val modelId: String = "",
    val modelLabel: String = "",
    val switcher: ModelSwitcher = ModelSwitcher(),
    /** The language the notes are written in ([NotesLanguage.Board] = the board's own), saved per notebook. */
    val language: NotesLanguage = NotesLanguage.Board,
) {
    /** The look of [noteTemplate]. */
    val style: StyleTemplate get() = noteTemplate.look
}

/** One choice in the header's quick switcher. [model] is an id, "" (automatic best) or [ModelCatalog.AUTO_FAST]. */
data class ModelOption(val model: String, val label: String, val selected: Boolean, val favorite: Boolean = false)

/** The header's quick switcher: every provider (configured or not), then the current provider's models. */
data class ModelSwitcher(
    val providers: List<Pair<AiProviderKind, Boolean>> = emptyList(),
    val options: List<ModelOption> = emptyList(),
    val loading: Boolean = false,
)

/**
 * Handwriting to insert as new pages on [template] paper: after page [afterIndex] of the notebook
 * Smart Board was opened from, or into a new notebook named [title] when [newNotebook].
 */
class SmartBoardResult(
    val template: PaperTemplate,
    val pages: List<List<InkStroke>>,
    /** Text boxes per page (font output), parallel to [pages]. */
    val elements: List<List<PageElement>> = emptyList(),
    val afterIndex: Int = -1,
    val newNotebook: Boolean = false,
    val title: String? = null,
    /** Page size the ink was laid out for (points). */
    val widthPt: Float = 0f,
    val heightPt: Float = 0f,
    /** The notes in the compact element format, stored with the pages (follow-up photos, glossary). */
    val notes: String? = null,
    val templateId: String? = null,
    /** A follow-up: these Smart Board pages are re-laid out (their user ink kept); extra pages go after the last. */
    val followUpPageIds: List<String> = emptyList(),
)

@HiltViewModel
class SmartBoardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val providers: AiProviders,
    private val styles: SmartBoardStyles,
    private val store: NotebookStore,
    private val templateStore: app.bestnote.engine.templates.TemplateStore,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<SmartBoardRoute>()

    /** The notebook page's size; paper starts as the page's own template when Smart Board can write on it. */
    private val pageSize = PageSize(route.widthPt, route.heightPt)
    private val startTemplate: PaperTemplate = PaperTemplate.entries.firstOrNull { it.name == route.template }
        ?.takeIf { it in RuledPaper.writable } ?: PaperTemplate.Ruled

    /** 1-based number of the page the notes go after (0 when unknown). */
    val afterPage: Int = route.pageIndex + 1

    fun paperFor(template: PaperTemplate): RuledPaper = RuledPaper.of(template, pageSize)

    private val settings = providers.settings
    private val startNoteTemplate = templateStore.byId(styles.templateIdFor(route.notebookId))
    private val state = MutableStateFlow(
        SmartBoardUiState(
            template = startNoteTemplate.look.paper ?: startTemplate,
            noteTemplate = startNoteTemplate,
            mode = styles.writingMode,
            language = styles.languageFor(route.notebookId),
            followUp = route.continuePageId != null,
            templates = templateStore.all(),
        ),
    )

    /** Re-reads the user's templates (after "Manage…" in the template editor). A deleted selection falls back to a built-in. */
    fun refreshTemplates() {
        val all = templateStore.all()
        state.update { s ->
            val current = all.firstOrNull { it.id == s.noteTemplate.id }
            if (current == null) s.copy(templates = all, noteTemplate = all.first(), template = all.first().look.paper ?: startTemplate)
            else s.copy(templates = all, noteTemplate = if (s.stage is SmartBoardStage.Review) s.noteTemplate else current, template = if (s.stage is SmartBoardStage.Review) s.template else current.look.paper ?: startTemplate)
        }
    }

    /** The notebook's Smart Board record (glossary, earlier pages), loaded once. */
    private var record = SmartBoardRecord()

    /** On a follow-up: the insert being continued. */
    private var group: SmartBoardGroup? = null
    private val reserved = ReservedLines.decode(route.reserved)
    private val notes by lazy { TemplateNotes(measure) }

    /** The template with the notebook's notes language applied (the language picker overrides the template's). */
    private fun effective(s: SmartBoardUiState = state.value): NoteTemplate {
        val t = s.noteTemplate
        return if (s.language == NotesLanguage.Board) t else t.copy(ai = t.ai.copy(notesLanguage = s.language.code))
    }
    private val measure by lazy {
        HandwritingTypefaces.init(context)
        PaintMeasure()
    }

    /** Writes the cached notes in the current template, mode and paper (off the main thread). */
    private suspend fun render(): Triple<BoardInk?, NotePages?, NoteDocument> = withContext(Dispatchers.Default) {
        val s = state.value
        val paper = paperFor(s.template)
        val pages = notes.render(effective(s), paper, record.glossary, s.fullGlossary, reserved, rtlHint = s.language.rtl)
        val legacy = LegacyNotes.toAiDocument(pages.doc)
        if (s.mode == WritingMode.Strokes && BoardToInk.strokesCanWrite(legacy)) {
            Triple(pipeline.write(legacy, pages.rtl, paper), null, pages.doc)
        } else {
            Triple(null, pages, pages.doc)
        }
    }

    private fun rerender() {
        val review = state.value.stage as? SmartBoardStage.Review ?: return
        viewModelScope.launch {
            val (ink, text, shown) = runCatching { render() }.getOrNull() ?: return@launch
            state.update { s -> if (s.stage === review) s.copy(stage = review.copy(ink = ink, text = text, notes = shown)) else s }
        }
    }

    private val newId = { UUID.randomUUID().toString() }

    /** The text boxes of a typed page, for the review preview (same elements that get inserted). */
    fun previewElements(page: app.bestnote.engine.handwriting.TypesetPage): List<PageElement> = TypesetElements.elements(page, measure, newId)

    /**
     * Picks the note template (remembered for this notebook so its pages match). Before capture it
     * only preselects; on the review screen the cached notes are re-drawn without an AI call when only
     * the look changed, and the board is read again when the AI instructions or elements changed.
     */
    fun setTemplate(template: NoteTemplate) {
        val before = state.value
        if (template.id == before.noteTemplate.id) return
        styles.setTemplateIdFor(route.notebookId, template.id)
        state.update { it.copy(noteTemplate = template, template = template.look.paper ?: startTemplate) }
        if (before.stage !is SmartBoardStage.Review) return
        val change = TemplateChange.between(effective(before), effective())
        state.update { it.copy(notice = change) }
        if (change == TemplateChange.NeedsAi) read() else rerender()
    }

    /** Adds the notebook glossary (all its terms) at the end of the page; no AI call. */
    fun insertGlossary(on: Boolean = true) {
        state.update { it.copy(fullGlossary = on) }
        rerender()
    }

    fun setMode(mode: WritingMode) {
        styles.writingMode = mode
        state.update { it.copy(mode = mode) }
        rerender()
    }
    val ui: StateFlow<SmartBoardUiState> = state.asStateFlow()

    /** Debuggable build, or "Verbose AI logging" on: show "Copy raw AI response" on failures. */
    private val showDiagnostics: Boolean
        get() = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 || settings.verboseLogging

    init {
        viewModelScope.launch {
            record = runCatching { SmartBoardRecord.decode(store.readExtra(NotebookId(route.notebookId), SmartBoardRecord.EXTRA)) }.getOrDefault(SmartBoardRecord())
            group = route.continuePageId?.let(record::groupFor)
            group?.let { g -> state.update { it.copy(noteTemplate = templateStore.byId(g.templateId), template = templateStore.byId(g.templateId).look.paper ?: startTemplate) } }
            state.update { it.copy(glossarySize = record.glossary.terms.size) }
        }
        viewModelScope.launch {
            refreshKey()
            // Keep the live model list fresh (at most daily) so "automatic" means the newest model.
            providers.refreshModelsIfStale()
            refreshKey()
        }
    }

    private suspend fun refreshKey() {
        val kind = providers.selected
        val configured = providers.isConfigured(kind)
        val stored = settings.storedModel(kind)
        val models = settings.cachedModels(kind)
        val id = settings.model(kind)
        fun name(m: String) = models.firstOrNull { it.id == m }?.displayName ?: m
        val favorites = settings.favoriteModels(kind)
        val options = buildList {
            if (kind != AiProviderKind.OpenAiCompatible) {
                add(ModelOption("", "★ " + name(ModelCatalog.resolve(kind, "", models)), stored.isBlank()))
                add(ModelOption(ModelCatalog.AUTO_FAST, "⚡ " + name(ModelCatalog.resolve(kind, ModelCatalog.AUTO_FAST, models)), stored == ModelCatalog.AUTO_FAST))
            }
            val ids = (favorites + settings.recentModels(kind) + ModelCatalog.sorted(models).take(SWITCHER_LIST).map { it.id }).distinct()
            ids.forEach { add(ModelOption(it, name(it), stored == it, it in favorites)) }
        }
        val all = AiProviderKind.entries.map { it to providers.isConfigured(it) }
        state.update {
            it.copy(
                provider = kind,
                hasKey = configured,
                keyHint = settings.keyHint(kind),
                modelId = id,
                modelLabel = name(id),
                switcher = ModelSwitcher(all, options),
            )
        }
    }

    /** Header quick switcher: another provider (its key and model come from Settings). */
    fun switchProvider(kind: AiProviderKind) {
        viewModelScope.launch {
            settings.setProvider(kind)
            refreshKey()
            state.update { it.copy(switcher = it.switcher.copy(loading = true)) }
            providers.refreshModelsIfStale(kind)
            refreshKey()
        }
    }

    /** Header quick switcher: a model id, "" (automatic best) or [ModelCatalog.AUTO_FAST]. */
    fun switchModel(model: String) {
        viewModelScope.launch {
            settings.setModel(providers.selected, model)
            refreshKey()
        }
    }

    private var job: Job? = null
    private val pipeline = BoardToInk { UUID.randomUUID().toString() }

    /** A file the camera app writes into (shared through this module's FileProvider). */
    fun cameraFile(): File = File(context.cacheDir, "smartboard").apply { mkdirs() }.resolve("board.jpg")

    private var nextPhotoId = 0L

    fun onPhoto(uri: Uri) = onPhotos(listOf(uri))

    /** Adds photos (camera or gallery): decoded, then the board's edges are searched on each. */
    fun onPhotos(uris: List<Uri>) {
        val room = MAX_PHOTOS - state.value.photos.size
        val take = uris.take(room.coerceAtLeast(0))
        if (take.isEmpty()) return
        state.update { it.copy(loading = it.loading + take.size, stage = SmartBoardStage.Capture) }
        viewModelScope.launch {
            var failed = 0
            for (uri in take) {
                val photo = withContext(Dispatchers.Default) {
                    val bitmap = runCatching { withContext(Dispatchers.IO) { decode(uri) } }.getOrNull() ?: return@withContext null
                    val found = BoardScan.detect(bitmap)
                    BoardPhoto(nextPhotoId++, bitmap, found?.quad ?: Quad.full(), found != null)
                }
                if (photo == null) failed++
                state.update { s ->
                    val photos = if (photo != null) s.photos + photo else s.photos
                    s.copy(photos = photos, current = if (photo != null) photos.lastIndex else s.current, loading = s.loading - 1, flatPreview = null)
                }
            }
            if (failed > 0 && state.value.photos.isEmpty()) state.update { it.copy(stage = SmartBoardStage.Failed(SmartBoardStage.Reason.Other)) }
        }
    }

    fun select(index: Int) = state.update { if (index in it.photos.indices) it.copy(current = index, flatPreview = null) else it }

    fun removePhoto(index: Int) = state.update { s ->
        if (index !in s.photos.indices) return@update s
        val photos = s.photos.toMutableList().apply { removeAt(index) }
        s.copy(photos = photos, current = s.current.coerceAtMost(photos.lastIndex).coerceAtLeast(0), flatPreview = null)
    }

    /** Moves one corner of the current photo's outline. */
    fun setQuad(quad: Quad) = state.update { s ->
        val photo = s.photos.getOrNull(s.current) ?: return@update s
        s.copy(photos = s.photos.toMutableList().apply { set(s.current, photo.copy(quad = quad)) })
    }

    /** Whole photo, or the detected board again. */
    fun resetQuad(whole: Boolean) {
        val s = state.value
        val photo = s.photos.getOrNull(s.current) ?: return
        viewModelScope.launch {
            val quad = if (whole) Quad.full(0f) else withContext(Dispatchers.Default) { BoardScan.detect(photo.bitmap)?.quad } ?: Quad.full()
            setQuad(quad)
            state.update { it.copy(flatPreview = null) }
        }
    }

    fun setEnhance(on: Boolean) {
        state.update { it.copy(enhance = on) }
        if (state.value.flatPreview != null) showPreview(true)
    }

    private var previewJob: Job? = null

    /** Shows (or hides) the current photo flattened and cleaned, exactly as the AI will see it. */
    fun showPreview(on: Boolean) {
        previewJob?.cancel()
        if (!on) return state.update { it.copy(flatPreview = null) }
        val s = state.value
        val photo = s.photos.getOrNull(s.current) ?: return
        previewJob = viewModelScope.launch {
            val flat = withContext(Dispatchers.Default) { BoardScan.flatten(photo.bitmap, photo.quad, s.enhance, PREVIEW_SIDE) }
            state.update { it.copy(flatPreview = flat) }
        }
    }

    /**
     * Picks the notes language (saved for this notebook). On the review screen the notes are read again
     * so they come back written in it.
     */
    fun setLanguage(language: NotesLanguage) {
        if (language == state.value.language) return
        styles.setLanguageFor(route.notebookId, language)
        state.update { it.copy(language = language) }
        if (state.value.stage is SmartBoardStage.Review) {
            state.update { it.copy(notice = TemplateChange.NeedsAi) }
            read()
        }
    }

    /** Back to the photos (they are kept, so the corners can be fixed and read again). */
    fun backToPhotos() {
        job?.cancel()
        state.update { it.copy(stage = SmartBoardStage.Capture) }
    }

    /** Stops the AI call in progress. */
    fun cancel() = backToPhotos()

    /** Starts over without photos. */
    fun retake() {
        job?.cancel()
        state.update { it.copy(stage = SmartBoardStage.Capture, photos = emptyList(), current = 0, flatPreview = null) }
    }

    fun saveKey(key: String?) {
        viewModelScope.launch {
            settings.setApiKey(providers.selected, key)
            refreshKey()
        }
    }

    /**
     * Reads every photo (flattened, enhanced) into the template's elements, or the built-in sample board
     * when there is no photo or key. Each further photo is merged into the notes so far (a follow-up
     * merges into the page's own notes), duplicates removed.
     */
    fun read() {
        val s = state.value
        val photos = s.photos
        val demo = photos.isEmpty() || !s.hasKey
        job?.cancel()
        job = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val startedAt = System.currentTimeMillis()
            val count = if (demo) 1 else photos.size
            try {
                val ticker = launch {
                    for (i in 0 until count) for (step in 0 until STEPS - 1) {
                        state.update { it.copy(stage = SmartBoardStage.Reading(step, i + 1, count)) }
                        delay(STEP_MS)
                    }
                }
                val images = if (demo) {
                    listOf(VisionImage(ByteArray(0), "image/jpeg"))
                } else {
                    withContext(Dispatchers.Default) { photos.map { VisionImage(BoardScan.jpeg(BoardScan.flatten(it.bitmap, it.quad, s.enhance)), "image/jpeg") } }
                }
                val provider: VisionProvider = if (demo) DemoVisionProvider() else providers.create(s.provider)
                try {
                    notes.read(provider, images, effective(), s.language.rtl, group?.notes)
                } finally {
                    ticker.cancel()
                }
                state.update { it.copy(stage = SmartBoardStage.Reading(STEPS - 1, count, count)) }
                val (ink, text, shown) = render()
                ink?.dropReasons.orEmpty().forEach { Log.i(TAG, "dropped $it") }
                state.update { it.copy(stage = SmartBoardStage.Review(ink, text, demo, SystemClock.elapsedRealtime() - started, shown)) }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (!demo) VisionDiagnostics.recordOutcome(error = e, since = startedAt)
                val last = VisionDiagnostics.latest()
                // Content-free: error class + provider message (redacted) + per-block drop reasons. Never the key.
                Log.w(TAG, "Smart Board failed (${s.provider.id}): ${e.javaClass.simpleName}: ${Redactor.redact(e.message.orEmpty())}")
                last?.parseNotes?.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "parse repairs: ${it.joinToString("; ")}") }
                last?.dropReasons?.forEach { Log.w(TAG, "dropped $it") }
                val reason = reasonOf(e)
                val diagnostics = if (showDiagnostics) last?.let(VisionDiagnostics::format) ?: e.toString() else null
                val detail = when {
                    e is VisionError.RateLimited && e.retryAfterSeconds != null -> context.getString(R.string.smartboard_error_retry_after, e.retryAfterSeconds)
                    showDiagnostics -> Redactor.redact(e.message.orEmpty()).take(DETAIL_CHARS)
                    else -> null
                }
                state.update { it.copy(stage = SmartBoardStage.Failed(reason, detail, diagnostics)) }
            }
        }
    }

    private fun reasonOf(e: Exception): SmartBoardStage.Reason = when (e) {
        is VisionError.BadResponse -> SmartBoardStage.Reason.NoBlocks
        is VisionError.MissingKey -> SmartBoardStage.Reason.MissingKey
        is VisionError.InvalidKey -> SmartBoardStage.Reason.InvalidKey
        is VisionError.RateLimited -> if (e.quotaExhausted) SmartBoardStage.Reason.QuotaExhausted else SmartBoardStage.Reason.RateLimited
        is VisionError.Network, is java.io.IOException -> SmartBoardStage.Reason.Network
        is VisionError.ModelUnavailable -> SmartBoardStage.Reason.ModelUnavailable
        is VisionError.ServiceUnavailable -> SmartBoardStage.Reason.ServerBusy
        is VisionError.Blocked -> SmartBoardStage.Reason.Blocked
        is VisionError.Truncated -> SmartBoardStage.Reason.Truncated
        else -> SmartBoardStage.Reason.Other
    }

    /** The reviewed pages as stored ink, with their notes for the notebook's Smart Board record. */
    fun result(newNotebook: Boolean = false): SmartBoardResult? {
        val review = state.value.stage as? SmartBoardStage.Review ?: return null
        val text = review.text
        val newId = { UUID.randomUUID().toString() }
        val title = review.notes.elements.firstOrNull { it.element == NoteElement.Title }?.let { it.text ?: it.head }
        return SmartBoardResult(
            template = review.paper.template,
            pages = text?.pages?.pages?.map { it.ink } ?: review.ink!!.pages.pages.map { page -> page.map { it.stroke } },
            elements = text?.pages?.pages?.map { TypesetElements.elements(it, measure, newId) }.orEmpty(),
            afterIndex = route.pageIndex,
            newNotebook = newNotebook,
            title = title?.takeIf { it.isNotBlank() },
            widthPt = review.paper.width,
            heightPt = review.paper.height,
            notes = NoteWire.encode(review.notes),
            templateId = state.value.noteTemplate.id,
            followUpPageIds = if (newNotebook) emptyList() else group?.pageIds.orEmpty(),
        )
    }

    private fun decode(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longSide = max(bounds.outWidth, bounds.outHeight)
        if (longSide <= 0) return null
        var sample = 1
        while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        if (rotation == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
    }

    companion object {
        /** Reading steps shown while the provider works (the last one is the handwriting). */
        const val STEPS = 6
        private const val STEP_MS = 380L
        /** Board text stays legible; fewer image tiles = fewer tokens and a faster answer (also bounds memory with several photos). */
        private const val MAX_SIDE = 1600
        private const val PREVIEW_SIDE = 1200
        const val MAX_PHOTOS = 6
        private const val TAG = "BestNoteSmartBoard"
        private const val DETAIL_CHARS = 400
        private const val SWITCHER_LIST = 8
    }
}
