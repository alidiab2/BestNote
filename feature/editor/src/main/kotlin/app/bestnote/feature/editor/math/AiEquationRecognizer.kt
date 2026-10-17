package app.bestnote.feature.editor.math

import app.bestnote.ai.math.EquationRecognizer
import app.bestnote.ai.vision.equationLatex
import app.bestnote.ai.vision.providers.AiProviders
import app.bestnote.ai.vision.providers.ImageCanary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Convert to equation" through the AI provider picked in Settings → Smart Board AI (MATH-01): the ink, as
 * black on white, goes to [AiProviders] with the strict LaTeX-only equation prompt. On-device maths
 * recognition (AI-10) will be another [EquationRecognizer].
 */
internal class AiEquationRecognizer(private val providers: AiProviders) : EquationRecognizer {
    override suspend fun isAvailable(): Boolean = runCatching { providers.isConfigured() }.getOrDefault(false)

    override suspend fun recognize(png: ByteArray): String {
        val image = withContext(Dispatchers.Default) { ImageCanary.fromEncoded(png, "image/png") }
        return providers.create().readEquation(image).equationLatex()
    }
}
