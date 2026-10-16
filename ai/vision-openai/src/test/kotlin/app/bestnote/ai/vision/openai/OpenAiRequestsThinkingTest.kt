package app.bestnote.ai.vision.openai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAiRequestsThinkingTest {
    @Test fun deepSeekGetsThinkingDisabled() {
        assertEquals(true, OpenAiConfig.turnsThinkingOff("https://api.deepseek.com/v1/"))
        assertFalse(OpenAiConfig.turnsThinkingOff("https://openrouter.ai/api/v1/"))
        val body = OpenAiRequests.chat("deepseek-flash", "sys", "user", ByteArray(4), "image/jpeg", ResponseFormat.JsonObject, false, thinkingOff = true)
        assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertNull(OpenAiRequests.chat("m", "sys", "user", ByteArray(4), "image/jpeg", ResponseFormat.JsonObject, false)["thinking"])
    }

    @Test fun rejectionIsRecognised() {
        assertEquals(true, OpenAiResponses.rejectsThinking(400, """{"error":{"message":"Unrecognized request argument supplied: thinking"}}"""))
        assertFalse(OpenAiResponses.rejectsThinking(400, """{"error":{"message":"response_format is unavailable"}}"""))
    }
}
