package app.bestnote.ai.vision.gemini

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

internal object Fixtures {
    fun read(name: String): String =
        requireNotNull(javaClass.classLoader!!.getResource("gemini/$name")) { "missing fixture $name" }.readText()

    /** A 200 `generateContent` response whose single part is [text]. */
    fun ok(text: String, finish: String = "STOP") =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":${Json.encodeToString(String.serializer(), text)}}]},"finishReason":"$finish"}]}"""

    fun error(code: Int, message: String, status: String = "X") =
        """{"error":{"code":$code,"message":"$message","status":"$status"}}"""

    const val NOTES = """{"blocks":[{"type":"heading","order":0,"level":1,"text":"Work"}]}"""
}
