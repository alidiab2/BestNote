package app.bestnote.ai.vision

import kotlinx.serialization.Serializable

/** One provider call as diagnostics see it. [raw] is only kept while [VisionDiagnostics.captureRaw] is on. */
@Serializable
data class AiExchange(
    val atMillis: Long,
    val provider: String,
    val model: String,
    val httpCode: Int,
    /** The response body, secrets redacted and capped at [VisionDiagnostics.MAX_RAW] chars. */
    val raw: String?,
    val parseNotes: List<String> = emptyList(),
    val dropReasons: List<String> = emptyList(),
    val error: String? = null,
    /** REQ-002: tokens the provider reported for this call (null when the body had no usage). */
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/** Token totals since the app started (REQ-002: to see where the AI spend goes). */
data class TokenTotals(val calls: Int = 0, val promptTokens: Long = 0, val completionTokens: Long = 0)

/** Reads the token usage every provider reports in its response body. Pure. */
object TokenUsage {
    private val PROMPT = listOf(
        Regex(""""prompt_tokens"\s*:\s*(\d+)"""), // OpenAI and compatible
        Regex(""""promptTokenCount"\s*:\s*(\d+)"""), // Gemini
        Regex(""""input_tokens"\s*:\s*(\d+)"""), // Claude
    )
    private val COMPLETION = listOf(
        Regex(""""completion_tokens"\s*:\s*(\d+)"""),
        Regex(""""candidatesTokenCount"\s*:\s*(\d+)"""),
        Regex(""""output_tokens"\s*:\s*(\d+)"""),
    )

    /** Gemini bills thinking as output but reports it apart. */
    private val THOUGHTS = Regex(""""thoughtsTokenCount"\s*:\s*(\d+)""")

    private fun last(patterns: List<Regex>, body: String): Int? =
        patterns.firstNotNullOfOrNull { p -> p.findAll(body).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }

    /** (prompt, completion incl. thinking) tokens in a response [body]; nulls when it reports none. */
    fun of(body: String?): Pair<Int?, Int?> {
        if (body.isNullOrEmpty()) return null to null
        val completion = last(COMPLETION, body)
        val thoughts = THOUGHTS.findAll(body).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        return last(PROMPT, body) to (if (completion == null && thoughts == null) null else (completion ?: 0) + (thoughts ?: 0))
    }
}

/**
 * In-memory record of the last few AI calls, for "Copy raw AI response" and the shared debug log.
 * Providers call [recordResponse]; the pipeline adds what it made of the answer with [recordOutcome].
 * Requests are never recorded (they carry the image), keys never reach here, and [Redactor] scrubs
 * anything key-shaped from bodies anyway.
 */
object VisionDiagnostics {
    const val MAX_RAW = 200_000
    private const val KEEP = 5

    /** Keep response bodies. On in debug builds; opt-in ("Verbose AI logging") in release. */
    @Volatile var captureRaw: Boolean = false

    /**
     * While a locked notebook is open (LIB-11b) response bodies are never kept, whatever [captureRaw] says: they hold
     * its handwriting as text, and the list is saved to disk for the debug log.
     */
    @Volatile var privateSession: Boolean = false

    /** Called after each change, e.g. to persist the list for the debug log. Off the main thread. */
    @Volatile var listener: ((List<AiExchange>) -> Unit)? = null

    private val entries = ArrayDeque<AiExchange>()

    fun recordResponse(provider: String, model: String, httpCode: Int, body: String?, clock: () -> Long = System::currentTimeMillis) {
        val raw = if (captureRaw && !privateSession) body?.let { Redactor.redact(it).take(MAX_RAW) } else null
        val (prompt, completion) = TokenUsage.of(body)
        synchronized(this) {
            totals = totals.copy(
                calls = totals.calls + 1,
                promptTokens = totals.promptTokens + (prompt ?: 0),
                completionTokens = totals.completionTokens + (completion ?: 0),
            )
        }
        update { add(AiExchange(clock(), provider, model, httpCode, raw, promptTokens = prompt, completionTokens = completion)) }
    }

    @Volatile private var totals = TokenTotals()

    /** Calls and tokens since the app started (or [resetTotals]); shown in the debug log. */
    fun totals(): TokenTotals = totals

    fun resetTotals() = synchronized(this) { totals = TokenTotals() }

    /**
     * Attaches parse notes, drop reasons and the final error to the latest call. With [since], a call
     * that failed before any response (offline, timeout) gets its own entry instead of being pinned on
     * an older exchange.
     */
    fun recordOutcome(parseNotes: List<String> = emptyList(), dropReasons: List<String> = emptyList(), error: Throwable? = null, since: Long? = null) {
        update {
            val stale = since != null && (lastOrNull()?.atMillis ?: 0L) < since
            val last = (if (stale) null else removeLastOrNull()) ?: AiExchange(System.currentTimeMillis(), "?", "?", 0, null)
            add(
                last.copy(
                    parseNotes = last.parseNotes + parseNotes,
                    dropReasons = last.dropReasons + dropReasons,
                    error = error?.let { "${it.javaClass.simpleName}: ${Redactor.redact(it.message.orEmpty())}" } ?: last.error,
                ),
            )
        }
    }

    fun latest(): AiExchange? = synchronized(entries) { entries.lastOrNull() }

    fun recent(): List<AiExchange> = synchronized(entries) { entries.toList() }

    fun clear() = update { clear() }

    /** Restores persisted entries (debug log survives a restart). */
    fun restore(saved: List<AiExchange>) = update {
        clear()
        addAll(saved.takeLast(KEEP))
    }

    private inline fun update(block: ArrayDeque<AiExchange>.() -> Unit) {
        val snapshot = synchronized(entries) {
            entries.block()
            while (entries.size > KEEP) entries.removeFirst()
            entries.toList()
        }
        listener?.let { l -> runCatching { l(snapshot) } }
    }

    /** Human-readable dump for the debug log and the clipboard. */
    fun format(e: AiExchange): String = buildString {
        append("time=").append(e.atMillis).append(" provider=").append(e.provider).append(" model=").append(e.model)
            .append(" http=").append(e.httpCode).append('\n')
        if (e.promptTokens != null || e.completionTokens != null) {
            append("tokens: prompt=").append(e.promptTokens ?: "?").append(" completion=").append(e.completionTokens ?: "?").append('\n')
        }
        totals().let { t -> append("session: calls=").append(t.calls).append(" prompt=").append(t.promptTokens).append(" completion=").append(t.completionTokens).append('\n') }
        e.error?.let { append("error: ").append(it).append('\n') }
        if (e.parseNotes.isNotEmpty()) append("parse: ").append(e.parseNotes.joinToString("; ")).append('\n')
        if (e.dropReasons.isNotEmpty()) append("dropped:\n").append(e.dropReasons.joinToString("\n") { "  - $it" }).append('\n')
        append("raw response:\n").append(e.raw ?: "(not captured; turn on Verbose AI logging)").append('\n')
    }
}

/** Scrubs API keys and auth headers from text before it is logged, copied or shared. */
object Redactor {
    private const val MASK = "[REDACTED]"
    private val patterns = listOf(
        Regex("""AIza[0-9A-Za-z_\-]{20,}"""), // Google
        Regex("""sk-ant-[0-9A-Za-z_\-]{8,}"""), // Anthropic
        Regex("""sk-(?:proj-|or-v1-)?[0-9A-Za-z_\-]{16,}"""), // OpenAI, OpenRouter
        Regex("""gsk_[0-9A-Za-z]{16,}"""), // Groq
        Regex("""(?i)\bBearer\s+[0-9A-Za-z._\-~+/=]{8,}"""),
        Regex("""(?i)((?:authorization|x-api-key|x-goog-api-key|api[_-]?key)["']?\s*[:=]\s*["']?)[^\s"',}]{6,}"""),
    )

    fun redact(text: String, extraSecrets: Collection<String> = emptyList()): String {
        var out = text
        extraSecrets.filter { it.length >= 6 }.forEach { out = out.replace(it, MASK) }
        patterns.forEachIndexed { i, p ->
            out = if (i == patterns.lastIndex) p.replace(out) { it.groupValues[1] + MASK } else p.replace(out, MASK)
        }
        return out
    }
}
