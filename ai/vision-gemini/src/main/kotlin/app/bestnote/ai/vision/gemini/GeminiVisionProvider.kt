package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.Prompts
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Gemini [VisionProvider] (AI-03) over the `generateContent` REST API, called with the user's own
 * key from [GeminiSettings]. Sends [Prompts] as the system instruction and constrains output with
 * JSON mode + [GeminiSchema] (`responseJsonSchema`; if an endpoint rejects that field, retried
 * once with the OpenAPI-style `responseSchema`). One retry on 5xx. Never logs the key.
 */
class GeminiVisionProvider(
    http: OkHttpClient,
    private val settings: GeminiSettings,
    private val config: GeminiConfig = GeminiConfig(),
) : VisionProvider {

    private val transport = GeminiHttp(http, config)

    override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument =
        generate(Prompts.board(request), BOARD_USER_TEXT, image)

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float): AiDocument =
        generate(Prompts.beautify(), beautifyUserText(widthPt, heightPt), selection)

    override suspend fun transcribe(selection: VisionImage, language: String?): AiDocument =
        generate(Prompts.transcribe(language), Prompts.TRANSCRIBE_USER, selection)

    private suspend fun generate(system: String, user: String, image: VisionImage): AiDocument {
        val key = settings.apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val model = settings.model().trim().ifEmpty { GeminiConfig.DEFAULT_MODEL }
        val mime = GeminiRequests.sniffMime(image.bytes, image.mimeType)
        var schemaField = SchemaField.JsonSchema
        var retriedServer = false
        while (true) {
            val body = GeminiRequests.generateContent(system, user, image.bytes, mime, config.temperature, schemaField, config.maxOutputTokens).toString()
            val response = try {
                transport.post("v1beta/models/$model:generateContent", key, body)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            }
            VisionDiagnostics.recordResponse("gemini", model, response.code, response.body)
            when {
                response.code == 200 -> {
                    val answer = GeminiResponses.answer(response.body)
                    return GeminiResponses.document(answer.text, answer.truncated)
                }
                schemaField == SchemaField.JsonSchema && GeminiResponses.rejectsJsonSchema(response.code, response.body) ->
                    schemaField = SchemaField.OpenApi
                schemaField == SchemaField.OpenApi && GeminiResponses.rejectsResponseSchema(response.code, response.body) ->
                    schemaField = SchemaField.None
                response.code in 500..599 && !retriedServer -> {
                    retriedServer = true
                    delay(config.retryDelayMs)
                }
                else -> throw GeminiResponses.errorFor(response.code, response.body, response.retryAfter, model)
            }
        }
    }

    internal companion object {
        const val BOARD_USER_TEXT = Prompts.BOARD_USER

        fun beautifyUserText(widthPt: Float, heightPt: Float) = Prompts.beautifyUser(widthPt, heightPt)
    }
}

/** Validates a key (and that it can see the model) with a cheap metadata call; for the settings screen. */
class GeminiKeyChecker(http: OkHttpClient, config: GeminiConfig = GeminiConfig()) {
    private val transport = GeminiHttp(http, config)

    /** Returns normally when the key works for [model]; throws a [VisionError] otherwise. */
    suspend fun check(apiKey: String, model: String = GeminiConfig.DEFAULT_MODEL) {
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val response = try {
            transport.get("v1beta/models/${model.trim()}", key)
        } catch (e: IOException) {
            throw VisionError.Network(e)
        }
        if (response.code != 200) throw GeminiResponses.errorFor(response.code, response.body, response.retryAfter, model)
    }

    /** The models this key can use for reading a board (see [GeminiModels]); follows up to 3 pages. */
    suspend fun listModels(apiKey: String): List<AiModel> {
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val out = ArrayList<AiModel>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val query = buildMap {
                put("pageSize", "1000")
                token?.let { put("pageToken", it) }
            }
            val response = try {
                transport.get("v1beta/models", key, query)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            }
            if (response.code != 200) throw GeminiResponses.errorFor(response.code, response.body, response.retryAfter, "")
            val page = runCatching { GeminiModels.parse(response.body) }.getOrElse { throw VisionError.BadResponse("model list is not JSON", it) }
            out += page.models
            token = page.nextPageToken ?: return out
        }
        return out
    }

    private companion object {
        const val MAX_PAGES = 3
    }
}
