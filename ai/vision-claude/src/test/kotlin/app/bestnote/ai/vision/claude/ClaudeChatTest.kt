package app.bestnote.ai.vision.claude

import app.bestnote.ai.vision.ChatTurn
import app.bestnote.ai.vision.ProviderCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ClaudeChatTest {
    @Test fun textOnlyMessagesCarrySystemAndTurns() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            val creds = object : ProviderCredentials {
                override suspend fun apiKey() = "placeholder-not-a-key"
                override suspend fun model() = "claude-test"
            }
            val provider = ClaudeVisionProvider(OkHttpClient(), creds, ClaudeConfig(baseUrl = server.url("/").toString(), retryDelayMs = 0))
            server.enqueue(MockResponse.Builder().code(200).body("""{"stop_reason":"end_turn","content":[{"type":"text","text":"{\"reply\":\"ok\"}"}]}""").build())
            assertEquals("""{"reply":"ok"}""", provider.completeText("sys", listOf(ChatTurn(true, "a"), ChatTurn(false, "b"), ChatTurn(true, "c"))))
            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("sys", body["system"]!!.jsonPrimitive.content)
            assertEquals(listOf("user", "assistant", "user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertFalse("no structured-output schema for chat", "output_config" in body)
        } finally {
            server.close()
        }
    }
}
