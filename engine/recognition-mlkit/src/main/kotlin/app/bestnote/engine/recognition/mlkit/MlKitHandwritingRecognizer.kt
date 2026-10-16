package app.bestnote.engine.recognition.mlkit

import android.util.Log
import app.bestnote.engine.recognition.HandwritingRecognizer
import app.bestnote.engine.recognition.InkStrokeSamples
import app.bestnote.engine.recognition.TextCandidate
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Handwriting → text with ML Kit Digital Ink, for the languages whose models are on the device (REC-01). */
@Singleton
class MlKitHandwritingRecognizer @Inject constructor(private val models: MlKitModels) : HandwritingRecognizer {
    private val clients = ConcurrentHashMap<String, DigitalInkRecognizer>()

    override val languages: Set<String> get() = models.state.value.ready

    override suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String): List<TextCandidate> {
        if (strokes.isEmpty() || languageTag !in languages) return emptyList()
        val client = clients[languageTag] ?: createClient(languageTag) ?: return emptyList()
        return try {
            client.recognize(ink(strokes), context(strokes)).await().candidates.map { TextCandidate(it.text, it.score ?: 0f) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(MlKitModels.TAG, "Recognizing $languageTag failed", e)
            emptyList()
        }
    }

    private fun createClient(tag: String): DigitalInkRecognizer? {
        val model = MlKitModels.modelOf(tag) ?: return null
        return DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()).also { clients[tag] = it }
    }

    internal companion object {
        /** Strokes keep their own clock (it restarts at 0 for each), so lay them end to end with a short pause between. */
        private const val PAUSE_MS = 100L

        fun ink(strokes: List<InkStrokeSamples>): Ink {
            val builder = Ink.builder()
            var offset = 0L
            for (stroke in strokes) {
                if (stroke.isEmpty()) continue
                val s = Ink.Stroke.builder()
                for (p in stroke) s.addPoint(Ink.Point.create(p.x, p.y, offset + p.timeMillis))
                builder.addStroke(s.build())
                offset += stroke.last().timeMillis + PAUSE_MS
            }
            return builder.build()
        }

        fun context(strokes: List<InkStrokeSamples>): RecognitionContext {
            val points = strokes.flatten()
            val w = (points.maxOfOrNull { it.x } ?: 0f) - (points.minOfOrNull { it.x } ?: 0f)
            val h = (points.maxOfOrNull { it.y } ?: 0f) - (points.minOfOrNull { it.y } ?: 0f)
            return RecognitionContext.builder().setWritingArea(WritingArea(w.coerceAtLeast(1f), h.coerceAtLeast(1f))).build()
        }
    }
}
