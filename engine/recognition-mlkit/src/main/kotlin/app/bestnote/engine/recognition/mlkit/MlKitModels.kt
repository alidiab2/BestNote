package app.bestnote.engine.recognition.mlkit

import android.util.Log
import app.bestnote.engine.recognition.HandwritingModels
import app.bestnote.engine.recognition.ModelStatus
import app.bestnote.engine.recognition.ModelsState
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ML Kit Digital Ink models for Arabic and English (REC-01). They are fetched once, on request, and
 * then recognition works offline. ML Kit reports no progress, so the UI shows "downloading" until done.
 */
@Singleton
class MlKitModels @Inject constructor() : HandwritingModels {
    override val languages: List<String> = LANGUAGES
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = RemoteModelManager.getInstance()
    private val mutable = MutableStateFlow(ModelsState())
    override val state: StateFlow<ModelsState> = mutable.asStateFlow()
    private var downloading = false

    init {
        scope.launch { refresh() }
    }

    /** Looks at which models are on the device. */
    suspend fun refresh() {
        if (downloading) return
        val ready = LANGUAGES.filter { isDownloaded(it) }.toSet()
        mutable.value = ModelsState(if (ready.size == LANGUAGES.size) ModelStatus.Ready else ModelStatus.NotDownloaded, ready)
    }

    override fun download() {
        if (downloading || mutable.value.isReady) return
        downloading = true
        mutable.value = mutable.value.copy(status = ModelStatus.Downloading)
        scope.launch {
            var failed = false
            for (tag in LANGUAGES) {
                if (tag in mutable.value.ready) continue
                try {
                    val model = modelOf(tag) ?: throw IllegalStateException("No model for $tag")
                    manager.download(model, DownloadConditions.Builder().build()).await()
                    mutable.value = mutable.value.copy(ready = mutable.value.ready + tag)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.w(TAG, "Model $tag failed to download", e)
                    failed = true
                }
            }
            downloading = false
            mutable.value = mutable.value.copy(status = if (failed) ModelStatus.Failed else ModelStatus.Ready)
        }
    }

    private suspend fun isDownloaded(tag: String): Boolean = try {
        modelOf(tag)?.let { manager.isModelDownloaded(it).await() } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "Cannot check model $tag", e)
        false
    }

    internal companion object {
        const val TAG = "BestNoteRecognition"
        val LANGUAGES = listOf("ar", "en-US")

        fun modelOf(tag: String): DigitalInkRecognitionModel? =
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)?.let { DigitalInkRecognitionModel.builder(it).build() }
    }
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
