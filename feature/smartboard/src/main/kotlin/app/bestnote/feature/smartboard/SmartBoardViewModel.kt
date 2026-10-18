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
import app.bestnote.core.model.EquationStyle
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
import app.bestnote.ai.vision.FontSlot
import app.bestnote.ai.vision.LocalRefine
import app.bestnote.ai.vision.LocalRefineCommands
import app.bestnote.ai.vision.LookPatch
import app.bestnote.ai.vision.RefineEngine
import app.bestnote.core.fonts.FontRegistry
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.ai.vision.AiProviderKind
import app.bestnote.ai.vision.ModelCatalog
import app.bestnote.ai.vision.Redactor
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.ai.vision.providers.ImageCanary
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

    enum class Reason { NoBlocks, MissingKey, InvalidKey, RateLimited, QuotaExhausted, Network, ModelUnavailable, ServerBusy, Blocked, Truncated, ImageNotSeen, Other }
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
    /** Set (to the model's name) when the chosen model is remembered as unable to read images; asks before calling. */
    val textOnlyWarning: String? = null,
    /** MATH-02: this notebook's equation style; null = the template's own. */
    val equationStyle: EquationStyle? = null,
    /** AI-13: colour and font changes on top of [noteTemplate]'s look. */
    val look: LookPatch = LookPatch(),
    val refine: RefineUi = RefineUi(),
    /** AI-13: an existing Smart Board page is open for changes (no photo); its template may change. */
    val refineOnly: Boolean = false,
    /** On a follow-up: the page's notes are known, so "Edit this page" can open it without a photo. */
    val canRefinePage: Boolean = false,
    /** AI-13: board photos stored with the page being continued (0 when none, or the notebook is locked). */
    val storedPhotos: Int = 0,
    /** AI-13: the photos shown in the original-photo viewer, while it is open. */
    val viewer: List<Bitmap>? = null,
    /** AI-13: re-running Smart Board on the stored photos (this page, or into a new page). */
    val regenerate: Regenerate? = null,
    /** The notebook is locked: keep its photos out of screenshots and recents. */
    val secure: Boolean = false,
) {
    /** The look of [noteTemplate]. */
    val style: StyleTemplate get() = look.applyTo(noteTemplate.look)
}

/** AI-13 "Ask Smart Board…": busy, the last friendly error, recent requests, undo. */
data class RefineUi(
    val busy: Boolean = false,
    val error: RefineError? = null,
    val requests: List<String> = emptyList(),
    val canUndo: Boolean = false,
    /** Ops the last AI edit could not apply (shown as a count, never the raw JSON). */
    val skipped: Int = 0,
)

enum class Regenerate { ThisPage, NewPage }

enum class RefineError { NeedsKey, NotUnderstood, Network, RateLimited, Other }

/** One choice in the header's quick switcher. [model] is an id, "" (automatic best) or [ModelCatalog.AUTO_FAST]. */
data class ModelOption(val model: String, val label: String, val selected: Boolean, val favorite: Boolean = false, val textOnly: Boolean = false)

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
    /** AI-13: the rectified board photos (JPEG) the notes were read from, kept with the pages. */
    val photos: List<ByteArray> = emptyList(),
    /** AI-12: picture files the [elements] use (asset path → PNG bytes), stored in the notebook on insert. */
    val assets: Map<String, ByteArray> = emptyMap(),
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
            equationStyle = styles.equationStyleFor(route.notebookId),
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
        val t = s.noteTemplate.let { n -> s.equationStyle?.let { n.copy(look = n.look.copy(equationStyle = it)) } ?: n }
        val looked = if (s.look.isEmpty) t else t.copy(look = s.look.applyTo(t.look))
        return if (s.language == NotesLanguage.Board) looked else looked.copy(ai = t.ai.copy(notesLanguage = s.language.code))
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

    /** AI-12: drawings redrawn from the board (and the pictures of those that could not be). */
    private val figures = BoardFigures()

    /** Pictures of the review preview (drawings kept as images). */
    val previewImages: app.bestnote.engine.canvas.document.ElementImages get() = figures.images

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
        if (before.stage is SmartBoardStage.Review) snapshot()
        styles.setTemplateIdFor(route.notebookId, template.id)
        state.update { it.copy(noteTemplate = template, template = template.look.paper ?: startTemplate) }
        if (before.stage !is SmartBoardStage.Review) return
        val change = TemplateChange.between(effective(before), effective())
        state.update { it.copy(notice = change) }
        if (change == TemplateChange.NeedsAi) read() else rerender()
    }

    /** MATH-02: draws this notebook's formulas as handwriting or typeset maths (null = follow the template); the notes are re-drawn, no AI call. */
    fun setEquationStyle(style: EquationStyle?) {
        if (style == state.value.equationStyle) return
        styles.setEquationStyleFor(route.notebookId, style)
        state.update { it.copy(equationStyle = style) }
        rerender()
    }

    // ------------------------------------------------------------ AI-13 · original photos, regenerate

    /** The stored photos of the page being continued (JPEG), and the photos the last reading sent. */
    private var storedPhotos: List<ByteArray> = emptyList()
    private var sentPhotos: List<ByteArray> = emptyList()

    private suspend fun loadStoredPhotos() {
        val paths = group?.photos.orEmpty()
        val id = NotebookId(route.notebookId)
        val secure = runCatching { store.isSealed(id) }.getOrDefault(false)
        if (paths.isEmpty()) return state.update { it.copy(secure = secure) }
        // A locked notebook that is not unlocked throws here: no photos, nothing shown.
        val assets = withContext(Dispatchers.IO) { runCatching { store.read(id)?.assets }.getOrNull() }.orEmpty()
        storedPhotos = paths.mapNotNull { assets[it] }
        state.update { it.copy(storedPhotos = storedPhotos.size, secure = secure) }
    }

    /** Opens the zoomable viewer on the page's stored photos (else this session's photos). */
    fun viewPhotos() {
        viewModelScope.launch {
            val shown = if (storedPhotos.isNotEmpty()) {
                withContext(Dispatchers.Default) { storedPhotos.mapNotNull { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            } else {
                state.value.photos.map { it.bitmap }
            }
            if (shown.isNotEmpty()) state.update { it.copy(viewer = shown) }
        }
    }

    fun closeViewer() = state.update { it.copy(viewer = null) }

    /**
     * Reads the stored photos again with the current template, prompt and parser: [Regenerate.ThisPage]
     * replaces the page's content (one undo step in the editor), [Regenerate.NewPage] inserts new pages.
     */
    fun regenerate(how: Regenerate) {
        if (storedPhotos.isEmpty()) return
        viewModelScope.launch {
            val photos = withContext(Dispatchers.Default) {
                storedPhotos.mapNotNull { BitmapFactory.decodeByteArray(it, 0, it.size) }.map { BoardPhoto(nextPhotoId++, it, Quad.full(0f), detected = true) }
            }
            if (photos.isEmpty()) return@launch
            // The stored photos are already flattened and cleaned.
            state.update { it.copy(photos = photos, current = 0, enhance = false, regenerate = how, refineOnly = true) }
            read()
        }
    }

    private fun plainJpeg(bitmap: Bitmap): ByteArray = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, it) }.toByteArray()

    // ------------------------------------------------------------ AI-13 · Ask Smart Board

    private val session = RefineSession()
    private val fonts by lazy { FontRegistry.get(context) }

    private fun snapshot() {
        val raw = notes.raw ?: return
        val s = state.value
        session.push(RefineSession.Snapshot(raw, s.look, s.noteTemplate.id))
        state.update { it.copy(refine = it.refine.copy(canUndo = true)) }
    }

    /** The script whose font "change the font" means: the notes' main script. */
    fun fontSlot(): FontSlot {
        val words = (state.value.stage as? SmartBoardStage.Review)?.notes?.elements?.joinToString(" ") { it.words() }.orEmpty()
        val arabic = words.count { it in '\u0600'..'\u06FF' }
        val hebrew = words.count { it in '\u0590'..'\u05FF' }
        val latin = words.count { it in 'a'..'z' || it in 'A'..'Z' }
        return when {
            arabic > latin && arabic >= hebrew -> FontSlot.Arabic
            hebrew > latin -> FontSlot.Hebrew
            else -> FontSlot.Latin
        }
    }

    /** The font id the page writes [slot] with now. */
    fun currentFont(slot: FontSlot = fontSlot()): String = effective().look.let {
        when (slot) {
            FontSlot.Latin -> it.latinFont.id
            FontSlot.Arabic -> it.arabicFont.id
            FontSlot.Hebrew -> it.hebrewFont.id
        }
    }

    /** Changes the page's handwriting font (no AI call, one undo step). */
    fun setFont(id: String?, slot: FontSlot = fontSlot()) {
        if (id == null || id == currentFont(slot)) return
        snapshot()
        state.update { it.copy(look = it.look + LookPatch(fonts = mapOf(slot to id)), refine = it.refine.copy(error = null)) }
        rerender()
    }

    /** Opens the Smart Board page being continued for changes, without a new photo. */
    fun openForRefine() {
        val g = group ?: return
        state.update { it.copy(refineOnly = true) }
        notes.restore(g.notes, effective())
        viewModelScope.launch {
            val (ink, text, shown) = runCatching { render() }.getOrNull() ?: return@launch
            state.update { it.copy(stage = SmartBoardStage.Review(ink, text, demo = false, tookMs = 0, notes = shown)) }
        }
    }

    /** "Ask Smart Board…": a local command (template, font, colour, undo) or an incremental AI edit of the notes. */
    fun refine(request: String, selection: Set<Int> = emptySet()) {
        val s = state.value
        val raw = notes.raw ?: return
        if (s.stage !is SmartBoardStage.Review || s.refine.busy || request.isBlank()) return
        session.remember(request)
        state.update { it.copy(refine = it.refine.copy(requests = session.requests, error = null, skipped = 0)) }
        val fontList = fonts.fonts.value.map { it.id to it.name }
        when (val local = LocalRefineCommands.parse(request, s.templates.map { it.id to it.name }, fontList, fontSlot())) {
            LocalRefine.Undo -> return undoRefine()
            is LocalRefine.Template -> return s.templates.firstOrNull { it.id == local.id }?.let(::setTemplate) ?: Unit
            is LocalRefine.Font -> return setFont(local.id, local.slot)
            is LocalRefine.Color -> {
                snapshot()
                state.update { it.copy(look = it.look + LookPatch(colors = local.roles.associateWith { local.color })) }
                return rerender()
            }
            null -> Unit
        }
        if (!s.hasKey) return state.update { it.copy(refine = it.refine.copy(error = RefineError.NeedsKey)) }
        state.update { it.copy(refine = it.refine.copy(busy = true)) }
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val out = RefineEngine.refine(providers.create(s.provider), raw, request, selection, s.look, fontList.map { it.first })
                snapshot()
                notes.restore(out.doc, effective())
                state.update { it.copy(look = out.look, refine = it.refine.copy(busy = false, skipped = out.skipped.size)) }
                out.skipped.forEach { Log.i(TAG, "refine skipped: $it") }
                rerender()
            } catch (e: CancellationException) {
                state.update { it.copy(refine = it.refine.copy(busy = false)) }
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "refine failed (${s.provider.id}): ${e.javaClass.simpleName}: ${Redactor.redact(e.message.orEmpty()).take(DETAIL_CHARS)}")
                val error = when (e) {
                    is VisionError.MissingKey, is VisionError.InvalidKey -> RefineError.NeedsKey
                    is VisionError.BadResponse, is VisionError.Truncated -> RefineError.NotUnderstood
                    is VisionError.Network, is java.io.IOException -> RefineError.Network
                    is VisionError.RateLimited, is VisionError.ServiceUnavailable -> RefineError.RateLimited
                    else -> RefineError.Other
                }
                state.update { it.copy(refine = it.refine.copy(busy = false, error = error)) }
            }
        }
    }

    /** Undoes the last refine (AI edit, template, font or colour). */
    fun undoRefine() {
        val before = session.pop() ?: return
        val template = state.value.templates.firstOrNull { it.id == before.templateId } ?: state.value.noteTemplate
        state.update { it.copy(look = before.look, noteTemplate = template, template = template.look.paper ?: startTemplate, notice = null, refine = it.refine.copy(canUndo = session.canUndo, error = null)) }
        notes.restore(before.notes, effective())
        rerender()
    }

    fun dismissRefineError() = state.update { it.copy(refine = it.refine.copy(error = null, skipped = 0)) }

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
    /**
     * Debug builds only: a recorded AI answer (`adb push answer.json /data/local/tmp/` then
     * `run-as <app> cp /data/local/tmp/answer.json files/debug/smartboard-answer.json`) stands in for the
     * AI on the photos, with no key and no call, so the real redraw and layout run on the device.
     */
    private fun recordedAnswer(hasPhotos: Boolean): java.io.File? {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable && hasPhotos) java.io.File(context.filesDir, "debug/smartboard-answer.json").takeIf { it.isFile } else null
    }

    private val showDiagnostics: Boolean
        get() = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 || settings.verboseLogging

    init {
        viewModelScope.launch {
            record = runCatching { SmartBoardRecord.decode(store.readExtra(NotebookId(route.notebookId), SmartBoardRecord.EXTRA)) }.getOrDefault(SmartBoardRecord())
            group = route.continuePageId?.let(record::groupFor)
            group?.let { g -> state.update { it.copy(noteTemplate = templateStore.byId(g.templateId), template = templateStore.byId(g.templateId).look.paper ?: startTemplate, canRefinePage = !g.notes.isEmpty) } }
            state.update { it.copy(glossarySize = record.glossary.terms.size) }
            loadStoredPhotos()
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
            ids.forEach { id -> add(ModelOption(id, name(id), stored == id, id in favorites, textOnly = id in settings.textOnlyModels(kind))) }
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
    fun cameraFile(): File = File(context.cacheDir, "smartboard").apply { mkdirs() }.resolve(CAMERA_FILE)

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
                    val bitmap = runCatching { withContext(Dispatchers.IO) { decode(uri) } }.getOrNull()
                    // The camera's plain copy goes once decoded (LIB-11b: the board may be for a locked notebook).
                    withContext(Dispatchers.IO) { if (uri.authority == context.packageName + ".smartboard.files" && uri.lastPathSegment == CAMERA_FILE) cameraFile().delete() }
                    if (bitmap == null) return@withContext null
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
        state.update { it.copy(stage = SmartBoardStage.Capture, photos = emptyList(), current = 0, flatPreview = null, regenerate = null) }
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
    fun read() = read(force = false)

    /** "Try anyway" after the text-only warning. */
    fun readAnyway() = read(force = true)

    fun dismissTextOnlyWarning() = state.update { it.copy(textOnlyWarning = null) }

    private fun read(force: Boolean) {
        val s = state.value
        val photos = s.photos
        val recorded = recordedAnswer(photos.isNotEmpty())
        val demo = (photos.isEmpty() || !s.hasKey) && recorded == null
        if (!demo && !force && s.modelId in settings.textOnlyModels(s.provider)) {
            // This model ignored a photo before: say so instead of spending a call on invented notes.
            state.update { it.copy(textOnlyWarning = s.modelLabel.ifBlank { s.modelId }) }
            return
        }
        state.update { it.copy(textOnlyWarning = null) }
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
                // The flattened boards are kept: AI-12 redraws the drawings from exactly what the AI saw.
                val boards = if (demo) emptyList() else withContext(Dispatchers.Default) { photos.map { BoardScan.flatten(it.bitmap, it.quad, s.enhance) } }
                val images = if (demo) {
                    // Photos without a key still go with the (sample) page, so they can be regenerated later.
                    sentPhotos = withContext(Dispatchers.Default) { photos.map { plainJpeg(BoardScan.flatten(it.bitmap, it.quad, s.enhance)) } }
                    listOf(VisionImage(ByteArray(0), "image/jpeg"))
                } else {
                    withContext(Dispatchers.Default) {
                        sentPhotos = boards.map(::plainJpeg)
                        boards.map { ImageCanary.jpeg(it) }
                    }
                }
                val provider: VisionProvider = when {
                    recorded != null -> RecordedVisionProvider(NoteWire.answer(recorded.readText()))
                    demo -> DemoVisionProvider()
                    else -> providers.create(s.provider)
                }
                try {
                    // Regenerate reads the photos afresh; a follow-up photo merges into the page's notes.
                    notes.read(provider, images, effective(), s.language.rtl, if (s.regenerate != null) null else group?.notes) { doc, i ->
                        boards.getOrNull(i)?.let { b -> runCatching { figures.resolve(doc, b) }.onFailure { Log.w(TAG, "figures: ${it.javaClass.simpleName}") }.getOrNull() } ?: doc
                    }
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
                // The model is now remembered as text-only: refresh the picker's "no images" badge.
                if (e is VisionError.ImageNotSeen) refreshKey()
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
        is VisionError.ImageNotSeen -> SmartBoardStage.Reason.ImageNotSeen
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
            assets = text?.pages?.pages?.flatMap { p -> p.images.map { it.asset } }.orEmpty().distinct().mapNotNull { a -> figures.assets[a]?.let { a to it } }.toMap(),
            afterIndex = route.pageIndex,
            newNotebook = newNotebook,
            title = title?.takeIf { it.isNotBlank() },
            widthPt = review.paper.width,
            heightPt = review.paper.height,
            notes = NoteWire.encode(review.notes),
            templateId = state.value.noteTemplate.id,
            followUpPageIds = if (newNotebook || state.value.regenerate == Regenerate.NewPage) emptyList() else group?.pageIds.orEmpty(),
            photos = when {
                state.value.regenerate != null || group == null -> sentPhotos
                else -> storedPhotos + sentPhotos
            },
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
        private const val CAMERA_FILE = "board.jpg"

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
        private const val PHOTO_QUALITY = 85
    }
}
