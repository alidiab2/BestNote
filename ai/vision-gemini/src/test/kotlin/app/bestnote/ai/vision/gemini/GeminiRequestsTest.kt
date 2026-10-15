package app.bestnote.ai.vision.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GeminiRequestsTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2)

    private fun JsonObject.obj(k: String) = getValue(k).jsonObject
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun buildsSystemImageTextAndJsonMode() {
        val body = GeminiRequests.generateContent("  system prompt \n", "user text", jpeg, "image/jpeg", 0.2)

        val system = body.obj("systemInstruction").getValue("parts").jsonArray.single().jsonObject
        assertEquals("system prompt", system.str("text"))

        val content = body.getValue("contents").jsonArray.single().jsonObject
        assertEquals("user", content.str("role"))
        val parts = content.getValue("parts").jsonArray.map { it.jsonObject }
        val inline = parts[0].obj("inlineData")
        assertEquals("image/jpeg", inline.str("mimeType"))
        assertEquals(Base64.getEncoder().encodeToString(jpeg), inline.str("data"))
        assertEquals("user text", parts[1].str("text"))

        val config = body.obj("generationConfig")
        assertEquals("application/json", config.str("responseMimeType"))
        assertEquals(0.2, config.str("temperature").toDouble(), 0.0)
        assertEquals(GeminiSchema.document, config["responseJsonSchema"])
        assertNull(config["responseSchema"])
    }

    @Test
    fun openApiVariantUsesResponseSchemaWithUpperCaseTypes() {
        val config = GeminiRequests.generateContent("s", "u", jpeg, "image/jpeg", 0.2, SchemaField.OpenApi).obj("generationConfig")
        assertNull(config["responseJsonSchema"])
        val schema = config.obj("responseSchema")
        assertEquals("OBJECT", schema.str("type"))
        val block = schema.obj("properties").obj("blocks").obj("items")
        assertEquals("ARRAY", schema.obj("properties").obj("blocks").str("type"))
        assertEquals("STRING", block.obj("properties").obj("type").str("type"))
        assertFalse(schema.toString().contains("\"string\""))
    }

    @Test
    fun schemaCoversTheProviderNeutralWireFormat() {
        val block = GeminiSchema.document.obj("properties").obj("blocks").obj("items")
        val props = block.obj("properties")
        listOf("type", "id", "order", "role", "level", "title", "text", "items", "tone", "latex", "boxed", "steps",
            "annotates", "semantic", "ink_color", "diagram", "source_box").forEach { assertTrue(it, it in props) }
        assertEquals(listOf("type"), block.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val types = props.obj("type").getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(types.containsAll(listOf("definition", "proof", "margin_note", "diagram")))
        val shape = props.obj("diagram").obj("properties").obj("shapes").obj("items")
        assertTrue("arrow" in shape.obj("properties").obj("kind").toString())
    }

    @Test
    fun sniffsImageTypeByMagicNumber() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)
        assertEquals("image/png", GeminiRequests.sniffMime(png, "image/jpeg"))
        assertEquals("image/jpeg", GeminiRequests.sniffMime(jpeg, "image/png"))
        assertEquals("image/webp", GeminiRequests.sniffMime(webp, "image/jpeg"))
        assertEquals("image/heic", GeminiRequests.sniffMime(byteArrayOf(1, 2, 3), "image/heic"))
    }
}
