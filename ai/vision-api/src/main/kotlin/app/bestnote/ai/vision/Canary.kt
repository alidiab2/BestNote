package app.bestnote.ai.vision

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * Proof that a model really received the photo. Some models (e.g. DeepSeek-V4-Pro through an OpenAI-compatible
 * endpoint) silently DROP image input and invent plausible notes. The app draws a random 4-digit code on a white
 * strip along the image's bottom edge; the prompt asks the model to return it as top-level `"seen"`, and
 * [verify] throws [VisionError.ImageNotSeen] when it is missing or wrong. Pure JVM: the Bitmap/AWT drawing
 * lives in the app layer and the live harness, and uses the geometry below.
 */
object Canary {
    /** A fresh code, 1000..9999 (no leading zero to misread). */
    fun newCode(random: Random = Random.Default): String = random.nextInt(1000, 10_000).toString()

    // ---- strip geometry, shared by the Android and AWT drawers

    /** Height of the white strip for an image of [width] x [height]. */
    fun stripHeight(width: Int, height: Int): Int = (minOf(width, height) * 0.14f).toInt().coerceIn(MIN_STRIP, MAX_STRIP)

    /** Digit height (px) that fits the strip and a [width]-wide image (four digits are about 2.4 em wide). */
    fun textSize(width: Int, stripHeight: Int): Float = minOf(stripHeight * 0.78f, width / 2.6f)

    private const val MIN_STRIP = 56
    private const val MAX_STRIP = 220

    // ---- prompt and schema

    /** Added to the system prompt when the image carries a canary. */
    fun instruction(): String =
        "\n\n## Image check\nA white strip along the image's bottom edge shows a 4-digit number. It is not part of the content: " +
            "never write it into the notes. Read it and return it exactly as the FIRST field of your JSON: " +
            "\"seen\":\"<the 4 digits>\"."

    fun system(system: String, image: VisionImage): String = if (image.canary == null) system else system + instruction()

    /** A prompt-only JSON [hint] (servers without a schema mode) plus the `seen` field. */
    fun hint(hint: String, image: VisionImage): String = if (image.canary == null) hint else "$hint Put \"seen\" (the strip's digits, as a string) first."

    /** [schema] with a required top-level string `seen`, listed first. */
    fun withSeen(schema: JsonObject, image: VisionImage): JsonObject {
        if (image.canary == null) return schema
        val props = schema["properties"] as? JsonObject ?: return schema
        val required = (schema["required"] as? JsonArray)?.toList().orEmpty()
        return JsonObject(
            schema + mapOf(
                "properties" to JsonObject(linkedMapOf<String, kotlinx.serialization.json.JsonElement>("seen" to buildJsonObject { put("type", "string") }).also { it.putAll(props) }),
                "required" to JsonArray(listOf(JsonPrimitive("seen")) + required.filter { it != JsonPrimitive("seen") }),
            ),
        )
    }

    // ---- reading the answer back

    private val SEEN = Regex(""""seen"\s*:\s*(?:"([^"]*)"|(\d+))""")

    /** The code the model returned, digits only, or null. Works on fenced, prose-wrapped or cut-off answers. */
    fun seen(answer: String): String? =
        SEEN.find(answer)?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).filter(Char::isDigit) }?.takeIf { it.isNotEmpty() }

    /**
     * Throws [VisionError.ImageNotSeen] when [image] carries a canary the model did not return. A cut-off answer
     * with no `seen` yet is left to the caller's truncation handling.
     */
    fun verify(answer: String, truncated: Boolean, image: VisionImage, model: String) {
        val expected = image.canary ?: return
        val got = seen(answer)
        if (got == expected) return
        if (got == null && truncated) return
        VisionDiagnostics.recordOutcome(parseNotes = listOf("canary: expected $expected, model returned ${got ?: "nothing"}"))
        throw VisionError.ImageNotSeen(model)
    }

    // ---- secondary signal: token counts

    private val TOKENS = listOf(
        Regex(""""prompt_tokens"\s*:\s*(\d+)"""), // OpenAI and compatible
        Regex(""""promptTokenCount"\s*:\s*(\d+)"""), // Gemini
        Regex(""""input_tokens"\s*:\s*(\d+)"""), // Claude
    )

    /** Prompt tokens the provider reported in a response body, or null. */
    fun promptTokens(body: String): Int? = TOKENS.firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * A diagnostics line when the reported prompt tokens are so few that the image cannot have been counted:
     * [textChars] of prompt text alone is about chars/4 tokens (JSON, maths and non-Latin text cost more), and
     * an image adds hundreds more. Conservative on purpose: it only logs.
     */
    fun tokenHint(promptTokens: Int?, textChars: Int): String? {
        if (promptTokens == null || textChars <= 0) return null
        val textOnly = textChars / 3
        return if (promptTokens <= textOnly) "prompt tokens $promptTokens <= $textOnly for $textChars chars of text: the image was probably not counted" else null
    }

    /** Records [tokenHint] for a response [body] in [VisionDiagnostics]. */
    fun noteUsage(body: String, textChars: Int) {
        tokenHint(promptTokens(body), textChars)?.let { VisionDiagnostics.recordOutcome(parseNotes = listOf(it)) }
    }
}
