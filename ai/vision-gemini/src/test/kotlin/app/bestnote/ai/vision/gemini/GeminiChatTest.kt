package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.ChatTurn
import app.bestnote.ai.vision.VisionError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** TPL-02: text-only completion (Design with AI) over a local MockWebServer. */
class GeminiChatTest {
    private val server = MockWebServer()
    private var key: String? = "placeholder-not-a-key"
    private lateinit var provider: GeminiVisionProvider

    @Before fun setUp() {
        server.start()
        val settings = object : GeminiSettings {
            override suspend fun apiKey() = key
            override suspend fun model() = "gemini-test"
        }
        provider = GeminiVisionProvider(OkHttpClient(), settings, GeminiConfig(baseUrl = server.url("/").toString(), retryDelayMs = 1))
    }

    @After fun tearDown() = server.close()

    private fun ok(text: String) = MockResponse.Builder().code(200).body(
        """{"candidates":[{"content":{"parts":[{"text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))}}]},"finishReason":"STOP"}]}""",
    ).build()

    @Test fun sendsSystemTurnsJsonModeAndLowThinkingWithoutAnImage() = runTest {
        server.enqueue(ok("""{"reply":"hi"}"""))
        val answer = provider.completeText("be a designer", listOf(ChatTurn(true, "blue headings")), json = true)
        assertEquals("""{"reply":"hi"}""", answer)
        val r = server.takeRequest()
        val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
        assertEquals("be a designer", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val turn = body["contents"]!!.jsonArray.single().jsonObject
        assertEquals("user", turn["role"]!!.jsonPrimitive.content)
        assertFalse("no image part", turn["parts"]!!.jsonArray.any { "inlineData" in it.jsonObject })
        val cfg = body["generationConfig"]!!.jsonObject
        assertEquals("application/json", cfg["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals("low", cfg["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
    }

    @Test fun missingKeyAndRejectedThinkingAreHandled() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"thinking level not supported"}}""").build())
        server.enqueue(ok("{}"))
        assertEquals("{}", provider.completeText("s", listOf(ChatTurn(true, "x"))))
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("1024", second["thinkingBudget"]!!.jsonPrimitive.content)
        key = null
        assertThrows(VisionError.MissingKey::class.java) { kotlinx.coroutines.runBlocking { provider.completeText("s", emptyList()) } }
    }
}
