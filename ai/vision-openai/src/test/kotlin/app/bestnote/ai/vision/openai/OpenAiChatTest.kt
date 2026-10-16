package app.bestnote.ai.vision.openai

import app.bestnote.ai.vision.ChatTurn
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatTest {
    private val turns = listOf(ChatTurn(true, "blue headings"))

    @Test fun jsonObjectModeLowEffortAndNoImage() {
        val b = OpenAiRequests.text("m", "sys", turns, ResponseFormat.JsonObject, official = true, reasoningEffort = "low")
        assertEquals("low", b["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("json_object", b["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("max_completion_tokens" in b && "temperature" !in b)
        val msgs = b["messages"]!!.jsonArray
        assertEquals(listOf("system", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertFalse(b.toString().contains("image_url"))
    }

    @Test fun compatibleServersGetMaxTokensAndAPromptHintWhenJsonModeIsDropped() {
        val b = OpenAiRequests.text("m", "sys", turns, ResponseFormat.None, official = false)
        assertTrue("max_tokens" in b && "response_format" !in b)
        assertTrue(b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.contains("JSON object only"))
    }
}
