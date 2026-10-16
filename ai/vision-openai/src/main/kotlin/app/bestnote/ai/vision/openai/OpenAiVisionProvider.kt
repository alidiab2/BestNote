package app.bestnote.ai.vision.openai

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.AiDocumentParser
import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.Prompts
import app.bestnote.ai.vision.ProviderCredentials
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * OpenAI [VisionProvider] over Chat Completions, also used for any OpenAI-compatible server
 * (OpenRouter, Groq, DeepSeek, a local Ollama) when [ProviderCredentials.baseUrl] is set.
 * Asks for `json_schema` output and steps down to `json_object`, then to prompt-only JSON, when a
 * server rejects the format. One retry on 5xx. Never logs the key.
 */
class OpenAiVisionProvider(
    http: OkHttpClient,
    private val credentials: ProviderCredentials,
    private val config: OpenAiConfig = OpenAiConfig(),
) : VisionProvider {
    private val transport = OpenAiHttp(http, config.timeoutSeconds)

    override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument =
        generate(Prompts.board(request), Prompts.BOARD_USER, image)

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float): AiDocument =
        generate(Prompts.beautify(), Prompts.beautifyUser(widthPt, heightPt), selection)

    override suspend fun transcribe(selection: VisionImage, language: String?): AiDocument =
        generate(Prompts.transcribe(language), Prompts.TRANSCRIBE_USER, selection)

    private suspend fun generate(system: String, user: String, image: VisionImage): AiDocument {
        val base = credentials.baseUrl()?.trim()?.takeIf { it.isNotEmpty() } ?: config.baseUrl
        val official = isOfficial(base)
        val key = credentials.apiKey()?.trim()?.takeIf { it.isNotEmpty() }
        // A local server may need no key; OpenAI always does.
        if (key == null && official) throw VisionError.MissingKey()
        val model = credentials.model().trim().ifEmpty { if (official) OpenAiConfig.DEFAULT_MODEL else throw VisionError.ModelUnavailable("", "no model name set") }
        val mime = OpenAiRequests.sniffMime(image.bytes, image.mimeType)
        val provider = if (official) "openai" else "openai-compatible"
        var format = ResponseFormat.JsonSchema
        var retriedServer = false
        while (true) {
            val body = OpenAiRequests.chat(model, system, user, image.bytes, mime, format, official, config).toString()
            val response = try {
                transport.post(OpenAiHttp.url(base, "chat/completions"), OpenAiRequests.headers(key, base), body)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            } catch (e: IllegalArgumentException) {
                throw VisionError.BadResponse("invalid base URL", e)
            }
            VisionDiagnostics.recordResponse(provider, model, response.code, response.body)
            when {
                response.code == 200 -> {
                    val answer = OpenAiResponses.answer(response.body)
                    return AiDocumentParser.answer(answer.text, answer.truncated)
                }
                format != ResponseFormat.None && OpenAiResponses.rejectsResponseFormat(response.code, response.body) ->
                    format = ResponseFormat.entries[format.ordinal + 1]
                response.code in 500..599 && !retriedServer -> {
                    retriedServer = true
                    delay(config.retryDelayMs)
                }
                else -> throw OpenAiResponses.errorFor(response.code, response.body, response.retryAfter, model)
            }
        }
    }

    companion object {
        fun isOfficial(baseUrl: String) = "api.openai.com" in baseUrl
    }
}

/**
 * "Test connection". OpenAI: free `GET /models/{model}`. Compatible servers: a 16-token
 * completion, because `/models` is often public (OpenRouter) and would not check the key.
 */
class OpenAiKeyChecker(http: OkHttpClient, private val config: OpenAiConfig = OpenAiConfig()) {
    private val transport = OpenAiHttp(http, 60)

    suspend fun check(apiKey: String?, model: String, baseUrl: String? = null) {
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: config.baseUrl
        val official = OpenAiVisionProvider.isOfficial(base)
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key == null && official) throw VisionError.MissingKey()
        val id = model.trim().ifEmpty { if (official) OpenAiConfig.DEFAULT_MODEL else throw VisionError.ModelUnavailable("", "no model name set") }
        val response = try {
            if (official) {
                transport.get(OpenAiHttp.url(base, "models/$id"), OpenAiRequests.headers(key, base))
            } else {
                transport.post(OpenAiHttp.url(base, "chat/completions"), OpenAiRequests.headers(key, base), OpenAiRequests.ping(id, official).toString())
            }
        } catch (e: IOException) {
            throw VisionError.Network(e)
        } catch (e: IllegalArgumentException) {
            throw VisionError.BadResponse("invalid base URL", e)
        }
        if (response.code != 200) throw OpenAiResponses.errorFor(response.code, response.body, response.retryAfter, id)
    }

    /** Chat models from `GET {base}/models` (see [OpenAiModels]); a local server may need no key. */
    suspend fun listModels(apiKey: String?, baseUrl: String? = null): List<AiModel> {
        val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: config.baseUrl
        val official = OpenAiVisionProvider.isOfficial(base)
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key == null && official) throw VisionError.MissingKey()
        val response = try {
            transport.get(OpenAiHttp.url(base, "models"), OpenAiRequests.headers(key, base))
        } catch (e: IOException) {
            throw VisionError.Network(e)
        } catch (e: IllegalArgumentException) {
            throw VisionError.BadResponse("invalid base URL", e)
        }
        if (response.code != 200) throw OpenAiResponses.errorFor(response.code, response.body, response.retryAfter, "")
        return runCatching { OpenAiModels.parse(response.body, official) }.getOrElse { throw VisionError.BadResponse("model list is not JSON", it) }
    }
}
