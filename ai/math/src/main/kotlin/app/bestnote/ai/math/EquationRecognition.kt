package app.bestnote.ai.math

/**
 * Reads handwritten maths from a picture of the ink (MATH-01). Today the user's AI provider does it
 * (Settings → Smart Board AI); an on-device math recognizer (AI-10) implements the same interface.
 */
interface EquationRecognizer {
    /** False when this recognizer cannot run now (e.g. no AI provider configured). */
    suspend fun isAvailable(): Boolean

    /**
     * The LaTeX the recognizer reads in [png] (black ink on white), as it gives it: possibly with `$` delimiters,
     * code fences or Unicode symbols. Empty when it sees no maths. Throws on failure (network, key…).
     */
    suspend fun recognize(png: ByteArray): String
}

/** What a reading of handwritten maths gave. */
sealed interface EquationReading {
    /** Clean, typesettable [latex]; [raw] is what the recognizer answered. */
    data class Ok(val latex: String, val raw: String) : EquationReading

    /** The recognizer saw no maths. */
    data object NotMath : EquationReading

    /** The answer could not be used safely (unbalanced groups, forbidden commands). */
    data class Invalid(val raw: String, val reason: String) : EquationReading
}

/** Turns a recognizer's raw answer into a typesettable formula with [LatexSanitizer]. Pure. */
object EquationReader {
    suspend fun read(recognizer: EquationRecognizer, png: ByteArray): EquationReading = interpret(recognizer.recognize(png))

    fun interpret(raw: String): EquationReading {
        var s = raw.trim()
        // ```latex … ``` from chatty models.
        FENCE.matchEntire(s)?.let { s = it.groupValues[1].trim() }
        // \begin{equation} … \end{equation} (and the starred form) only wrap the maths.
        WRAPPER.matchEntire(s)?.let { s = it.groupValues[2].trim() }
        if (s.isEmpty() || s.equals("none", ignoreCase = true)) return EquationReading.NotMath
        return when (val r = LatexSanitizer.sanitize(s)) {
            is LatexResult.Ok -> EquationReading.Ok(r.latex, raw)
            is LatexResult.Invalid -> if (r.reason == "empty") EquationReading.NotMath else EquationReading.Invalid(raw, r.reason)
        }
    }

    private val FENCE = Regex("""^```[a-zA-Z]*\s*(.*?)\s*```$""", RegexOption.DOT_MATCHES_ALL)
    private val WRAPPER = Regex("""^\\begin\{(equation\*?|displaymath|math)\}(.*)\\end\{\1\}$""", RegexOption.DOT_MATCHES_ALL)
}
