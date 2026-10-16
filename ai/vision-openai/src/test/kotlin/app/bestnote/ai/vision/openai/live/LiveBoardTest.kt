package app.bestnote.ai.vision.openai.live

import app.bestnote.ai.vision.Canary
import app.bestnote.ai.vision.NoteWire
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.ProviderCredentials
import app.bestnote.ai.vision.VisionDiagnostics
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.openai.OpenAiVisionProvider
import app.bestnote.engine.layout.AiInstructions
import app.bestnote.engine.layout.NoteTemplates
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * One REAL Smart Board call against the owner's DeepSeek key, for improving prompts and elements.
 * Never runs in CI or a normal `test`: it needs `scripts/agent/ai-live.sh`, which checks the call
 * budget in ~/.config/bestnote/ai-budget and leaves a one-shot request file. The key lives only in
 * ~/.config/bestnote/deepseek.env (outside the repo) and is never printed.
 */
class LiveBoardTest {
    private val home = File(System.getProperty("user.home"), ".config/bestnote")

    @Test fun boardPhotoToNotes() = runBlocking {
        val request = File(home, "live-request")
        assumeTrue("run through scripts/agent/ai-live.sh", request.exists())
        val args = request.readLines().associate { it.substringBefore('=') to it.substringAfter('=') }
        request.delete() // one call per request, even if this test fails
        val env = File(home, "deepseek.env").readLines().filter { '=' in it && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val creds = object : ProviderCredentials {
            override suspend fun apiKey() = env.getValue("DEEPSEEK_API_KEY")
            override suspend fun model() = args["model"] ?: env.getValue("DEEPSEEK_MODEL")
            override suspend fun baseUrl() = env.getValue("DEEPSEEK_BASE_URL")
        }
        val template = NoteTemplates.builtIns().first { it.id == (args["template"] ?: "study-columns") }.let {
            it.copy(ai = it.ai.copy(notesLanguage = args["notes"]?.ifBlank { null }, termsLanguage = args["terms"]?.ifBlank { null }, note = args["note"].orEmpty()))
        }
        VisionDiagnostics.captureRaw = true
        val started = System.currentTimeMillis()
        val model = creds.model()
        val image = jpeg(File(args.getValue("image")))
        val out = File(args.getValue("out")).apply { mkdirs() }
        val doc = try {
            OpenAiVisionProvider(OkHttpClient(), creds).writeNotes(image, NotesRequest(template, rtl = args["rtl"] == "1"))
        } catch (e: VisionError.ImageNotSeen) {
            File(out, "raw.txt").writeText(VisionDiagnostics.latest()?.raw.orEmpty())
            File(out, "summary.txt").writeText("model=$model result=ImageNotSeen expected=${image.canary} prompt_tokens=${promptTokens()}\n")
            throw e
        }
        File(out, "notes.json").writeText(NoteWire.encode(doc))
        File(out, "raw.txt").writeText(VisionDiagnostics.latest()?.raw.orEmpty())
        File(out, "summary.txt").writeText(
            "model=$model result=ok canary=${image.canary} (model returned it) prompt_tokens=${promptTokens()} " +
                "ms=${System.currentTimeMillis() - started} elements=${doc.flatten().size} kinds=${doc.flatten().groupingBy { it.element }.eachCount()}\n",
        )
    }

    private fun promptTokens() = VisionDiagnostics.latest()?.raw?.let(Canary::promptTokens)

    /** The app's capture path: longest side 1600 px, a white strip with a random 4-digit code at the bottom edge (see [Canary]), JPEG. */
    private fun jpeg(file: File): VisionImage {
        val src = ImageIO.read(file)
        val k = minOf(1f, 1600f / maxOf(src.width, src.height))
        val w = (src.width * k).toInt()
        val h = (src.height * k).toInt()
        val strip = Canary.stripHeight(w, h)
        val code = Canary.newCode()
        val img = BufferedImage(w, h + strip, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            color = Color.WHITE
            fillRect(0, 0, w, h + strip)
            drawImage(src, 0, 0, w, h, null)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            font = Font(Font.SANS_SERIF, Font.BOLD, 10).deriveFont(Canary.textSize(w, strip))
            color = Color.BLACK
            drawString(code, (w - fontMetrics.stringWidth(code)) / 2, h + (strip + fontMetrics.ascent - fontMetrics.descent) / 2)
            dispose()
        }
        return VisionImage(ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray(), "image/jpeg", code)
    }
}
