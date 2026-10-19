package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.Canary
import app.bestnote.ai.vision.AiSchema
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.NoteMerge
import app.bestnote.ai.vision.NotePrompts
import app.bestnote.ai.vision.NoteSchema
import app.bestnote.ai.vision.NoteWire
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.engine.layout.NoteDocument
import kotlinx.serialization.json.JsonObject
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

    override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument =
        generate(NotePrompts.system(request), NotePrompts.user(request), image, { NoteSchema.document(request.template, it) }) { text, truncated ->
            NoteWire.answer(text, truncated).let { d -> if (request.current != null) NoteMerge.dedupe(d) else d }
        }

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?): AiDocument =
        generate(Prompts.beautify(language), beautifyUserText(widthPt, heightPt), selection)

    override suspend fun transcribe(selection: VisionImage, language: String?): AiDocument =
        generate(Prompts.transcribe(language), Prompts.TRANSCRIBE_USER, selection)

    override suspend fun readEquation(selection: VisionImage): AiDocument =
        generate(Prompts.equation(), Prompts.EQUATION_USER, selection)

    override suspend fun completeText(system: String, turns: List<app.bestnote.ai.vision.ChatTurn>, json: Boolean): String {
        val key = settings.apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val model = settings.model().trim().ifEmpty { GeminiConfig.DEFAULT_MODEL }
        var thinking = Thinking.Level
        var retriedServer = false
        while (true) {
            val body = GeminiRequests.chat(system, turns, 0.4, CHAT_MAX_TOKENS, thinking, json).toString()
            val response = try {
                transport.post("v1beta/models/$model:generateContent", key, body)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            }
            when {
                response.code == 200 -> return GeminiResponses.answer(response.body).text
                thinking != Thinking.None && GeminiResponses.rejectsThinking(response.code, response.body) -> thinking = Thinking.entries[thinking.ordinal + 1]
                response.code in 500..599 && !retriedServer -> {
                    retriedServer = true
                    delay(config.retryDelayMs)
                }
                else -> throw GeminiResponses.errorFor(response.code, response.body, response.retryAfter, model)
            }
        }
    }

    private suspend fun generate(system: String, user: String, image: VisionImage): AiDocument =
        generate(system, user, image, { AiSchema.document(it) }) { text, truncated -> GeminiResponses.document(text, truncated) }

    private suspend fun <T> generate(
        system0: String,
        user: String,
        image: VisionImage,
        schema0: (AiSchema.Dialect) -> JsonObject,
        parse: (String, Boolean) -> T,
    ): T {
        val system = Canary.system(system0, image)
        val schema: (AiSchema.Dialect) -> JsonObject = { Canary.withSeen(schema0(it), image) }
        val key = settings.apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val model = settings.model().trim().ifEmpty { GeminiConfig.DEFAULT_MODEL }
        val mime = GeminiRequests.sniffMime(image.bytes, image.mimeType)
        var schemaField = SchemaField.JsonSchema
        var thinking = Thinking.Level
        var retriedServer = false
        var budget = config.maxOutputTokens
        while (true) {
            val body = GeminiRequests.generateContent(system, user, image.bytes, mime, config.temperature, schemaField, budget, thinking, schema).toString()
            val response = try {
                transport.post("v1beta/models/$model:generateContent", key, body)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            }
            VisionDiagnostics.recordResponse("gemini", model, response.code, response.body)
            if (response.code == 200) Canary.noteUsage(response.body, system.length + user.length)
            when {
                response.code == 200 -> {
                    val answer = try {
                        GeminiResponses.answer(response.body)
                    } catch (e: VisionError.Truncated) {
                        // Nothing written: the budget went to thinking. One retry with more room; a cut-off
                        // answer WITH text is salvaged by [parse] instead of being sent again.
                        if (budget >= GeminiConfig.RETRY_OUTPUT_TOKENS) throw e
                        budget = GeminiConfig.RETRY_OUTPUT_TOKENS
                        continue
                    }
                    Canary.verify(answer.text, answer.truncated, image, model)
                    return parse(answer.text, answer.truncated)
                }
                thinking != Thinking.None && GeminiResponses.rejectsThinking(response.code, response.body) ->
                    thinking = Thinking.entries[thinking.ordinal + 1]
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
        const val CHAT_MAX_TOKENS = 8_000

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
