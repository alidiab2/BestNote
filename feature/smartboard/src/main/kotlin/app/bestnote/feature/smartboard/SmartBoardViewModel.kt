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
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.DemoVisionProvider
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.ai.vision.gemini.GeminiVisionProvider
import app.bestnote.ai.vision.gemini.KeystoreGeminiSettings
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.LayoutTemplate
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.RectPt
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
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/** What Smart Board shows: capture → reading → the handwritten page. */
sealed interface SmartBoardStage {
    /** Choosing a photo, then the region of the board to read. */
    data object Capture : SmartBoardStage

    /** [step] of [SmartBoardViewModel.STEPS] is in progress. */
    data class Reading(val step: Int) : SmartBoardStage

    data class Review(val ink: BoardInk, val demo: Boolean, val tookMs: Long) : SmartBoardStage

    data class Failed(val reason: Reason) : SmartBoardStage

    enum class Reason { NoBlocks, InvalidKey, RateLimited, Network, Other }
}

data class SmartBoardUiState(
    val stage: SmartBoardStage = SmartBoardStage.Capture,
    /** The photo being cropped (downscaled for the screen and the provider). */
    val photo: Bitmap? = null,
    /** The region to read, 0..1 of the photo. */
    val crop: RectPt = RectPt(0.04f, 0.04f, 0.96f, 0.96f),
    val hasKey: Boolean = false,
    val keyHint: String? = null,
    val arabic: Boolean = false,
)

/** Handwriting to insert into the notebook as new pages, on [template] paper. */
class SmartBoardResult(val template: PaperTemplate, val pages: List<List<InkStroke>>)

@HiltViewModel
class SmartBoardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<SmartBoardRoute>()

    /** The paper the notes are written on: the notebook page's size, ruled (college) when the page isn't. */
    val template: PaperTemplate = PaperTemplate.entries.firstOrNull { it.name == route.template }
        ?.takeIf(RuledPaper::isRuled) ?: PaperTemplate.Ruled
    val paper: RuledPaper = RuledPaper.of(template, PageSize(route.widthPt, route.heightPt))

    private val settings = KeystoreGeminiSettings(context)
    private val state = MutableStateFlow(SmartBoardUiState(hasKey = settings.hasApiKey, keyHint = settings.apiKeyHint))
    val ui: StateFlow<SmartBoardUiState> = state.asStateFlow()

    private var job: Job? = null
    private val pipeline = BoardToInk { UUID.randomUUID().toString() }

    /** A file the camera app writes into (shared through this module's FileProvider). */
    fun cameraFile(): File = File(context.cacheDir, "smartboard").apply { mkdirs() }.resolve("board.jpg")

    fun onPhoto(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { decode(uri) }.getOrNull() }
            if (bitmap == null) {
                state.update { it.copy(stage = SmartBoardStage.Failed(SmartBoardStage.Reason.Other)) }
                return@launch
            }
            state.update { it.copy(photo = bitmap, crop = SmartBoardUiState().crop, stage = SmartBoardStage.Capture) }
        }
    }

    fun setCrop(crop: RectPt) = state.update { it.copy(crop = crop) }

    fun setArabic(arabic: Boolean) = state.update { it.copy(arabic = arabic) }

    fun retake() {
        job?.cancel()
        state.update { it.copy(stage = SmartBoardStage.Capture, photo = null) }
    }

    fun saveKey(key: String?) {
        viewModelScope.launch {
            settings.setApiKey(key)
            state.update { it.copy(hasKey = settings.hasApiKey, keyHint = settings.apiKeyHint) }
        }
    }

    /** Reads the cropped photo, or the built-in sample board when there is no photo. */
    fun read() {
        val s = state.value
        val photo = s.photo
        val demo = photo == null || !s.hasKey
        job?.cancel()
        job = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val ticker = launch {
                for (i in 0 until STEPS - 1) {
                    state.update { it.copy(stage = SmartBoardStage.Reading(i)) }
                    delay(STEP_MS)
                }
            }
            try {
                val image = withContext(Dispatchers.Default) { photo?.let { crop(it, s.crop) } ?: VisionImage(ByteArray(0), "image/jpeg") }
                val provider: VisionProvider = if (demo) DemoVisionProvider() else GeminiVisionProvider(OkHttpClient(), settings)
                val request = BoardRequest(LayoutTemplate.LectureSummary, rtl = s.arabic, language = if (s.arabic) "ar" else null)
                val doc = provider.reconstructBoard(image, request)
                ticker.join()
                state.update { it.copy(stage = SmartBoardStage.Reading(STEPS - 1)) }
                val ink = withContext(Dispatchers.Default) { pipeline.write(doc, s.arabic, paper) }
                state.update { it.copy(stage = SmartBoardStage.Review(ink, demo, SystemClock.elapsedRealtime() - started)) }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "Smart Board failed: ${e.javaClass.simpleName}")
                ticker.cancel()
                val reason = when (e) {
                    is VisionError.BadResponse -> SmartBoardStage.Reason.NoBlocks
                    is VisionError.InvalidKey, is VisionError.MissingKey -> SmartBoardStage.Reason.InvalidKey
                    is VisionError.RateLimited -> SmartBoardStage.Reason.RateLimited
                    is VisionError.Network -> SmartBoardStage.Reason.Network
                    else -> SmartBoardStage.Reason.Other
                }
                state.update { it.copy(stage = SmartBoardStage.Failed(reason)) }
            }
        }
    }

    /** The reviewed pages as stored ink. */
    fun result(): SmartBoardResult? {
        val review = state.value.stage as? SmartBoardStage.Review ?: return null
        return SmartBoardResult(template, review.ink.pages.pages.map { page -> page.map { it.stroke } })
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

    private fun crop(bitmap: Bitmap, r: RectPt): VisionImage {
        val l = (r.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val t = (r.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val w = ((r.right - r.left) * bitmap.width).roundToInt().coerceIn(1, bitmap.width - l)
        val h = ((r.bottom - r.top) * bitmap.height).roundToInt().coerceIn(1, bitmap.height - t)
        val cut = Bitmap.createBitmap(bitmap, l, t, w, h)
        val out = ByteArrayOutputStream()
        cut.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return VisionImage(out.toByteArray(), "image/jpeg")
    }

    companion object {
        /** Reading steps shown while the provider works (the last one is the handwriting). */
        const val STEPS = 6
        private const val STEP_MS = 380L
        private const val MAX_SIDE = 2048
        private const val JPEG_QUALITY = 88
        private const val TAG = "BestNoteSmartBoard"
    }
}
