package app.bestnote.ai.vision.openai

import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.ai.vision.ProviderCredentials
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.core.model.LayoutTemplate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private fun fixture(name: String) = requireNotNull(OpenAiRequestsTest::class.java.classLoader!!.getResource("openai/$name")).readText()

class OpenAiRequestsTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    @Test
    fun officialRequestUsesJsonSchemaAndMaxCompletionTokens() {
        val body = OpenAiRequests.chat("gpt-5-mini", "sys", "user", png, "image/png", ResponseFormat.JsonSchema, official = true)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("system", messages[0].getValue("role").jsonPrimitive.content)
        assertEquals("sys", messages[0].getValue("content").jsonPrimitive.content)
        val parts = messages[1].getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("text", parts[0].getValue("type").jsonPrimitive.content)
        val url = parts[1].getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        assertTrue(url.startsWith("data:image/png;base64,"))
        val format = body.getValue("response_format").jsonObject
        assertEquals("json_schema", format.getValue("type").jsonPrimitive.content)
        assertEquals("false", format.getValue("json_schema").jsonObject.getValue("strict").jsonPrimitive.content)
        assertEquals("16000", body.getValue("max_completion_tokens").jsonPrimitive.content)
        assertNull(body["max_tokens"])
        assertNull(body["temperature"])
    }

    @Test
    fun compatibleRequestUsesMaxTokensAndCanDropTheFormat() {
        val body = OpenAiRequests.chat("llava", "sys", "user", png, "image/png", ResponseFormat.None, official = false)
        assertNull(body["response_format"])
        assertEquals("16000", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("0.2", body.getValue("temperature").jsonPrimitive.content)
        // Without a schema the prompt spells out the JSON shape.
        assertTrue(body.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content.contains("\"blocks\""))
        val jsonObject = OpenAiRequests.chat("m", "s", "u", png, "image/png", ResponseFormat.JsonObject, official = false)
        assertEquals("json_object", jsonObject.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun headers() {
        assertEquals("Bearer k", OpenAiRequests.headers("k", OpenAiConfig.OPENAI_BASE_URL)["Authorization"])
        assertNull(OpenAiRequests.headers("", "http://localhost:11434/v1/")["Authorization"])
        assertEquals("BestNote", OpenAiRequests.headers("k", "https://openrouter.ai/api/v1/")["X-Title"])
    }

    @Test
    fun parsesAnswersRefusalsAndTruncation() {
        assertTrue(OpenAiResponses.answer(fixture("board_mixed.json")).text.contains("المتتاليات"))
        assertTrue(OpenAiResponses.answer(fixture("board_truncated.json")).truncated)
        try {
            OpenAiResponses.answer(fixture("refusal.json"))
            fail()
        } catch (_: VisionError.Blocked) {
        }
    }

    @Test
    fun mapsErrors() {
        assertTrue(OpenAiResponses.errorFor(401, fixture("invalid_key.json"), null, "m") is VisionError.InvalidKey)
        assertTrue(OpenAiResponses.errorFor(404, fixture("model_not_found.json"), null, "gpt-nope") is VisionError.ModelUnavailable)
        assertTrue(OpenAiResponses.rejectsResponseFormat(400, fixture("format_rejected.json")))
        assertFalse(OpenAiResponses.rejectsResponseFormat(400, fixture("invalid_key.json")))
        assertEquals(3L, (OpenAiResponses.errorFor(429, "{}", "3", "m") as VisionError.RateLimited).retryAfterSeconds)
        assertTrue(OpenAiResponses.errorFor(503, "busy", null, "m") is VisionError.ServiceUnavailable)
    }
}

/** End-to-end over a local MockWebServer standing in for OpenAI or a compatible server. */
class OpenAiVisionProviderTest {
    private val server = MockWebServer()
    private var key: String? = "placeholder-not-a-key"
    private var model = "qwen2.5vl:7b"
    private lateinit var provider: OpenAiVisionProvider
    private val jpeg = VisionImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9), "image/jpeg")
    private val request = BoardRequest(LayoutTemplate.LectureSummary)

    private fun respond(code: Int, body: String) = MockResponse.Builder().code(code).body(body).build()
    private fun takeJson(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Before
    fun setUp() {
        server.start()
        val base = server.url("/v1").toString() // no trailing slash on purpose
        val creds = object : ProviderCredentials {
            override suspend fun apiKey() = key
            override suspend fun model() = model
            override suspend fun baseUrl() = base
        }
        provider = OpenAiVisionProvider(OkHttpClient(), creds, OpenAiConfig(retryDelayMs = 0))
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun sendsLowReasoningEffortAndDropsItWhenRejected() = runTest {
        server.enqueue(respond(400, """{"error":{"message":"Unrecognized request argument supplied: reasoning_effort"}}"""))
        server.enqueue(respond(200, fixture("ollama_fenced.json")))
        provider.reconstructBoard(jpeg, request)
        assertEquals("low", takeJson().getValue("reasoning_effort").jsonPrimitive.content)
        assertNull(takeJson()["reasoning_effort"])
    }

    @Test
    fun answerLostToThinkingIsRetriedOnceWithMoreRoom() = runTest {
        val allThinking = """{"choices":[{"index":0,"message":{"role":"assistant","content":"","reasoning_content":"Let me analyze..."},"finish_reason":"length"}]}"""
        server.enqueue(respond(200, allThinking))
        server.enqueue(respond(200, fixture("ollama_fenced.json")))
        val doc = provider.reconstructBoard(jpeg, request)
        assertEquals("Newton's second law", doc.title)
        assertEquals(16_000, takeJson().getValue("max_tokens").jsonPrimitive.content.toInt())
        assertEquals(32_000, takeJson().getValue("max_tokens").jsonPrimitive.content.toInt())
    }

    @Test
    fun compatibleServerStepsDownTheResponseFormat() = runTest {
        server.enqueue(respond(400, fixture("format_rejected.json")))
        server.enqueue(respond(400, """{"error":{"message":"response_format json_object is not supported"}}"""))
        server.enqueue(respond(200, fixture("ollama_fenced.json")))
        val doc = provider.reconstructBoard(jpeg, request)
        assertEquals("Newton's second law", doc.title)
        assertEquals("json_schema", takeJson().getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("json_object", takeJson().getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        assertNull(takeJson()["response_format"])
    }

    private val canaryImage = VisionImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 9), "image/jpeg", canary = "4821")
    private val notesRequest = NotesRequest(NoteTemplates.builtIns().first())

    @Test
    fun modelThatDropsThePhotoIsImageNotSeenAndTheNotesAreNeverReturned() = runTest {
        // Recorded: DeepSeek-V4-Pro answered a maths board with a confident lesson on Newton's laws (744 prompt tokens, no image).
        model = "deepseek-v4-pro"
        server.enqueue(respond(200, fixture("deepseek_pro_dropped_image.json")))
        VisionDiagnostics.clear()
        try {
            provider.writeNotes(canaryImage, notesRequest)
            fail("invented notes must not come back")
        } catch (e: VisionError.ImageNotSeen) {
            assertEquals("deepseek-v4-pro", e.model)
        }
        // The request asked for the code, in the prompt and in the schema.
        val body = takeJson()
        assertTrue(body.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content.contains("\"seen\""))
        assertTrue(body.toString().contains("\"required\":[\"seen\""))
        assertTrue(VisionDiagnostics.latest()!!.parseNotes.any { it.contains("expected 4821") })
    }

    @Test
    fun modelThatSeesThePhotoReturnsTheCodeAndTheNotes() = runTest {
        model = "deepseek-flash"
        server.enqueue(respond(200, fixture("deepseek_flash_seen.json")))
        val doc = provider.writeNotes(canaryImage, notesRequest)
        assertEquals("Partial fractions", doc.elements.first().text)
    }

    @Test
    fun imagesWithoutACanaryAreNotChecked() = runTest {
        server.enqueue(respond(200, fixture("deepseek_pro_dropped_image.json")))
        provider.writeNotes(jpeg, notesRequest)
        assertFalse(takeJson().toString().contains("\"seen\""))
    }

    @Test
    fun localServerWithoutKeyAndPathJoin() = runTest {
        key = null
        server.enqueue(respond(200, fixture("board_mixed.json")))
        provider.reconstructBoard(jpeg, request)
        val r = server.takeRequest()
        assertEquals("/v1/chat/completions", r.url.encodedPath)
        assertNull(r.headers["Authorization"])
    }

    @Test
    fun truncatedAndErrors() = runTest {
        server.enqueue(respond(200, fixture("board_truncated.json")))
        assertEquals(4, provider.reconstructBoard(jpeg, request).blocks.size)
        server.enqueue(respond(404, fixture("model_not_found.json")))
        try { provider.reconstructBoard(jpeg, request); fail() } catch (_: VisionError.ModelUnavailable) { }
        server.enqueue(respond(500, "x"))
        server.enqueue(respond(502, "x"))
        try { provider.reconstructBoard(jpeg, request); fail() } catch (_: VisionError.ServiceUnavailable) { }
    }

    @Test
    fun officialOpenAiNeedsAKey() = runTest {
        val p = OpenAiVisionProvider(
            OkHttpClient(),
            object : ProviderCredentials {
                override suspend fun apiKey(): String? = null
                override suspend fun model() = ""
            },
        )
        try { p.reconstructBoard(jpeg, request); fail() } catch (_: VisionError.MissingKey) { }
    }

    @Test
    fun keyCheckerPingsCompatibleServers() = runTest {
        server.enqueue(respond(200, fixture("board_mixed.json")))
        OpenAiKeyChecker(OkHttpClient()).check("k", "llava", server.url("/v1/").toString())
        val r = server.takeRequest()
        assertEquals("/v1/chat/completions", r.url.encodedPath)
        assertEquals("16", Json.parseToJsonElement(r.body!!.utf8()).jsonObject.getValue("max_tokens").jsonPrimitive.content)
    }

    @Test
    fun listsCompatibleModels() = runTest {
        server.enqueue(respond(200, fixture("models_ollama.json")))
        val models = OpenAiKeyChecker(OkHttpClient()).listModels(null, server.url("/v1/").toString())
        assertEquals(listOf("qwen2.5vl:7b"), models.map { it.id })
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)
    }
}

class OpenAiModelsTest {
    @Test
    fun officialListKeepsChatModelsAndFoldsSnapshots() {
        val models = OpenAiModels.parse(fixture("models_openai.json"), official = true)
        assertEquals(listOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna", "gpt-5-mini", "o4-mini"), models.map { it.id })
        val pick = app.bestnote.ai.vision.ModelCatalog.pick(models, app.bestnote.ai.vision.ModelCatalog.Preference.Quality)
        assertEquals("gpt-6-astra", pick!!.id)
        assertEquals("gpt-6-luna", app.bestnote.ai.vision.ModelCatalog.pick(models, app.bestnote.ai.vision.ModelCatalog.Preference.Fast)!!.id)
    }

    @Test
    fun openRouterModalitiesMarkVision() {
        val models = OpenAiModels.parse(fixture("models_openrouter.json"), official = false)
        assertEquals(3, models.size)
        assertEquals(false, models.first { it.id == "deepseek/deepseek-v4" }.vision)
        assertEquals("Google: Gemini 3.8 Flash", models.first().displayName)
        assertEquals(1776000000L, models.first().created)
    }
}
