package app.bestnote.ai.vision

/** One provider call as diagnostics see it. [raw] is only kept while [VisionDiagnostics.captureRaw] is on. */
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
)

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

    /** Called after each change, e.g. to persist the list for the debug log. Off the main thread. */
    @Volatile var listener: ((List<AiExchange>) -> Unit)? = null

    private val entries = ArrayDeque<AiExchange>()

    fun recordResponse(provider: String, model: String, httpCode: Int, body: String?, clock: () -> Long = System::currentTimeMillis) {
        val raw = if (captureRaw) body?.let { Redactor.redact(it).take(MAX_RAW) } else null
        update { add(AiExchange(clock(), provider, model, httpCode, raw)) }
    }

    /** Attaches parse notes, drop reasons and the final error to the latest call. */
    fun recordOutcome(parseNotes: List<String> = emptyList(), dropReasons: List<String> = emptyList(), error: Throwable? = null) {
        update {
            val last = removeLastOrNull() ?: AiExchange(System.currentTimeMillis(), "?", "?", 0, null)
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
