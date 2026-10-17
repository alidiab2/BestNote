package app.bestnote.ai.vision.openai

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.AiDocumentParser
import app.bestnote.ai.vision.AiModel
import app.bestnote.ai.vision.AiSchema
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.Canary
import app.bestnote.ai.vision.NoteMerge
import app.bestnote.ai.vision.NotePrompts
import app.bestnote.ai.vision.NoteSchema
import app.bestnote.ai.vision.NoteWire
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.engine.layout.NoteDocument
import kotlinx.serialization.json.JsonObject
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

    override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument =
        generate(NotePrompts.system(request), NotePrompts.user(request), image, NoteSchema.document(request.template, AiSchema.Dialect.Closed), NOTES_JSON_HINT) { text, truncated ->
            NoteWire.answer(text, truncated).let { d -> if (request.current != null) NoteMerge.dedupe(d) else d }
        }

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?): AiDocument =
        generate(Prompts.beautify(language), Prompts.beautifyUser(widthPt, heightPt), selection)

    override suspend fun transcribe(selection: VisionImage, language: String?): AiDocument =
        generate(Prompts.transcribe(language), Prompts.TRANSCRIBE_USER, selection)

    override suspend fun readEquation(selection: VisionImage): AiDocument =
        generate(Prompts.equation(), Prompts.EQUATION_USER, selection)

    override suspend fun completeText(system: String, turns: List<app.bestnote.ai.vision.ChatTurn>, json: Boolean): String {
        val base = credentials.baseUrl()?.trim()?.takeIf { it.isNotEmpty() } ?: config.baseUrl
        val official = isOfficial(base)
        val key = credentials.apiKey()?.trim()?.takeIf { it.isNotEmpty() }
        if (key == null && official) throw VisionError.MissingKey()
        val model = credentials.model().trim().ifEmpty { if (official) OpenAiConfig.DEFAULT_MODEL else throw VisionError.ModelUnavailable("", "no model name set") }
        var format = if (json) ResponseFormat.JsonObject else ResponseFormat.None
        var effort: String? = config.reasoningEffort
        var thinkingOff = OpenAiConfig.turnsThinkingOff(base)
        var budget = config.maxTokens
        var retriedServer = false
        var retriedBudget = false
        while (true) {
            val body = OpenAiRequests.text(model, system, turns, format, official, config, effort, budget, thinkingOff).toString()
            val response = try {
                transport.post(OpenAiHttp.url(base, "chat/completions"), OpenAiRequests.headers(key, base), body)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            } catch (e: IllegalArgumentException) {
                throw VisionError.BadResponse("invalid base URL", e)
            }
            when {
                response.code == 200 -> {
                    try {
                        return OpenAiResponses.answer(response.body).text
                    } catch (e: VisionError.Truncated) {
                        if (retriedBudget) throw e
                        retriedBudget = true
                        if (effort != null) effort = MIN_EFFORT
                        budget = (budget * 2).coerceAtMost(MAX_BUDGET)
                    }
                }
                thinkingOff && OpenAiResponses.rejectsThinking(response.code, response.body) -> thinkingOff = false
                thinkingOff && OpenAiResponses.rejectsThinking(response.code, response.body) -> thinkingOff = false
                effort != null && OpenAiResponses.rejectsReasoningEffort(response.code, response.body) -> effort = null
                format == ResponseFormat.JsonObject && OpenAiResponses.rejectsResponseFormat(response.code, response.body) -> format = ResponseFormat.None
                response.code in 500..599 && !retriedServer -> {
                    retriedServer = true
                    delay(config.retryDelayMs)
                }
                else -> throw OpenAiResponses.errorFor(response.code, response.body, response.retryAfter, model)
            }
        }
    }

    private suspend fun generate(system: String, user: String, image: VisionImage): AiDocument =
        generate(system, user, image, AiSchema.document(AiSchema.Dialect.Closed), Prompts.JSON_ONLY_HINT) { text, truncated -> AiDocumentParser.answer(text, truncated) }

    private suspend fun <T> generate(system0: String, user: String, image: VisionImage, schema0: JsonObject, jsonHint0: String, parse: (String, Boolean) -> T): T {
        val system = Canary.system(system0, image)
        val schema = Canary.withSeen(schema0, image)
        val jsonHint = Canary.hint(jsonHint0, image)
        val base = credentials.baseUrl()?.trim()?.takeIf { it.isNotEmpty() } ?: config.baseUrl
        val official = isOfficial(base)
        val key = credentials.apiKey()?.trim()?.takeIf { it.isNotEmpty() }
        // A local server may need no key; OpenAI always does.
        if (key == null && official) throw VisionError.MissingKey()
        val model = credentials.model().trim().ifEmpty { if (official) OpenAiConfig.DEFAULT_MODEL else throw VisionError.ModelUnavailable("", "no model name set") }
        val mime = OpenAiRequests.sniffMime(image.bytes, image.mimeType)
        val provider = if (official) "openai" else "openai-compatible"
        var format = ResponseFormat.JsonSchema
        var effort: String? = config.reasoningEffort
        var thinkingOff = OpenAiConfig.turnsThinkingOff(base)
        var budget = config.maxTokens
        var retriedServer = false
        var retriedBudget = false
        while (true) {
            val body = OpenAiRequests.chat(model, system, user, image.bytes, mime, format, official, config, effort, budget, schema, jsonHint, thinkingOff).toString()
            val response = try {
                transport.post(OpenAiHttp.url(base, "chat/completions"), OpenAiRequests.headers(key, base), body)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            } catch (e: IllegalArgumentException) {
                throw VisionError.BadResponse("invalid base URL", e)
            }
            VisionDiagnostics.recordResponse(provider, model, response.code, response.body)
            if (response.code == 200) Canary.noteUsage(response.body, system.length + user.length)
            when {
                response.code == 200 -> {
                    val answer = try {
                        OpenAiResponses.answer(response.body)
                    } catch (e: VisionError.Truncated) {
                        // All of the budget went to thinking: one retry with the least effort and more room.
                        if (retriedBudget) throw e
                        retriedBudget = true
                        if (effort != null) effort = MIN_EFFORT
                        budget = (budget * 2).coerceAtMost(MAX_BUDGET)
                        continue
                    }
                    Canary.verify(answer.text, answer.truncated, image, model)
                    return parse(answer.text, answer.truncated)
                }
                effort != null && OpenAiResponses.rejectsReasoningEffort(response.code, response.body) -> effort = null
                budget > config.maxTokens && OpenAiResponses.rejectsMaxTokens(response.code, response.body) -> budget = config.maxTokens
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

        /** Lowest `reasoning_effort` most thinking models accept; used for the one retry after a cut-off. */
        private const val MIN_EFFORT = "low"

        /** For servers without a schema mode: the element shape, spelled out. */
        const val NOTES_JSON_HINT = "Answer with one JSON object {\"elements\": [{\"e\": element, ...}]} only, no prose and no code fences."
        private const val MAX_BUDGET = 32_000
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
