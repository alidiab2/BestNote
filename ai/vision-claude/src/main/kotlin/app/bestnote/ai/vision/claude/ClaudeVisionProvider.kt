package app.bestnote.ai.vision.claude

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.AiDocumentParser
import app.bestnote.ai.vision.AiModel
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
import app.bestnote.ai.vision.ProviderCredentials
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Claude [VisionProvider] over the Messages API (`POST /v1/messages`) with the user's own key.
 * The expert-student prompt is the system prompt, the photo goes in as a base64 image block, and
 * the answer is constrained to [app.bestnote.ai.vision.AiSchema] with `output_config.format`.
 * Refusals use the server-side `fallbacks: "default"` where the model supports it. One retry on
 * 5xx/529; a 400 about structured output or fallbacks is retried once without that feature.
 * Raw HTTP (OkHttp) like the other providers: one endpoint, no SDK on the APK.
 */
class ClaudeVisionProvider(
    http: OkHttpClient,
    private val credentials: ProviderCredentials,
    private val config: ClaudeConfig = ClaudeConfig(),
) : VisionProvider {
    private val transport = ClaudeHttp(http, config.timeoutSeconds)

    override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument =
        generate(Prompts.board(request), Prompts.BOARD_USER, image)

    override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument =
        generate(NotePrompts.system(request), NotePrompts.user(request), image, NoteSchema.document(request.template, AiSchema.Dialect.Closed)) { text, truncated ->
            NoteWire.answer(text, truncated).let { d -> if (request.current != null) NoteMerge.dedupe(d) else d }
        }

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?): AiDocument =
        generate(Prompts.beautify(language), Prompts.beautifyUser(widthPt, heightPt), selection)

    override suspend fun transcribe(selection: VisionImage, language: String?): AiDocument =
        generate(Prompts.transcribe(language), Prompts.TRANSCRIBE_USER, selection)

    private suspend fun generate(system: String, user: String, image: VisionImage): AiDocument =
        generate(system, user, image, AiSchema.document(AiSchema.Dialect.Closed)) { text, truncated -> AiDocumentParser.answer(text, truncated) }

    private suspend fun <T> generate(system: String, user: String, image: VisionImage, schema: JsonObject, parse: (String, Boolean) -> T): T {
        val key = credentials.apiKey()?.trim()?.takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val model = credentials.model().trim().ifEmpty { ClaudeConfig.DEFAULT_MODEL }
        val base = credentials.baseUrl()?.takeIf { it.isNotBlank() } ?: config.baseUrl
        val mime = ClaudeRequests.sniffMime(image.bytes, image.mimeType)
        var structured = true
        var fallbacks = model in ClaudeConfig.FALLBACK_MODELS
        var retriedServer = false
        while (true) {
            val body = ClaudeRequests.messages(model, system, user, image.bytes, mime, config.maxTokens, structured, fallbacks, schema).toString()
            val response = try {
                transport.post(ClaudeHttp.url(base, "v1/messages"), ClaudeRequests.headers(key, fallbacks), body)
            } catch (e: IOException) {
                throw VisionError.Network(e)
            }
            VisionDiagnostics.recordResponse("claude", model, response.code, response.body)
            when {
                response.code == 200 -> {
                    val answer = ClaudeResponses.answer(response.body)
                    return parse(answer.text, answer.truncated)
                }
                fallbacks && ClaudeResponses.rejectsFallbacks(response.code, response.body) -> fallbacks = false
                structured && ClaudeResponses.rejectsStructuredOutput(response.code, response.body) -> structured = false
                (response.code in 500..599) && !retriedServer -> {
                    retriedServer = true
                    delay(config.retryDelayMs)
                }
                else -> throw ClaudeResponses.errorFor(response.code, response.body, response.retryAfter, model)
            }
        }
    }
}

/** "Test connection": a free metadata call that checks the key and that it can see the model. */
class ClaudeKeyChecker(http: OkHttpClient, private val config: ClaudeConfig = ClaudeConfig()) {
    private val transport = ClaudeHttp(http, 30)

    suspend fun check(apiKey: String, model: String = ClaudeConfig.DEFAULT_MODEL) {
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val id = model.trim().ifEmpty { ClaudeConfig.DEFAULT_MODEL }
        val response = try {
            transport.get(ClaudeHttp.url(config.baseUrl, "v1/models/$id"), ClaudeRequests.headers(key, fallbacks = false))
        } catch (e: IOException) {
            throw VisionError.Network(e)
        }
        if (response.code != 200) throw ClaudeResponses.errorFor(response.code, response.body, response.retryAfter, id)
    }

    /** Every model this key can use, newest first as the API returns them (up to 3 pages of 1000). */
    suspend fun listModels(apiKey: String): List<AiModel> {
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: throw VisionError.MissingKey()
        val out = ArrayList<AiModel>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = ClaudeHttp.url(config.baseUrl, "v1/models").newBuilder()
                .addQueryParameter("limit", "1000")
                .apply { after?.let { addQueryParameter("after_id", it) } }
                .build()
            val response = try {
                transport.get(url, ClaudeRequests.headers(key, fallbacks = false))
            } catch (e: IOException) {
                throw VisionError.Network(e)
            }
            if (response.code != 200) throw ClaudeResponses.errorFor(response.code, response.body, response.retryAfter, "")
            val page = runCatching { ClaudeModels.parse(response.body) }.getOrElse { throw VisionError.BadResponse("model list is not JSON", it) }
            out += page.models
            if (!page.hasMore || page.lastId == null) return out
            after = page.lastId
        }
        return out
    }

    private companion object {
        const val MAX_PAGES = 3
    }
}
