package app.bestnote.engine.recognition

/** The text of some handwriting, read line by line. */
data class Transcript(val lines: List<String>) {
    val text: String get() = lines.joinToString("\n")
    val isEmpty: Boolean get() = lines.all { it.isBlank() }
}

/**
 * Handwriting → text for a lasso selection (REC-05). Each line is recognized in every language and the
 * reading in the script it was written in wins ([ReadingChooser]); the result keeps the page's own line breaks.
 */
class Transcriber(private val recognizer: HandwritingRecognizer, private val languages: List<String> = listOf("ar", "en-US")) {
    suspend fun transcribe(strokes: List<InkStrokeSamples>): Transcript {
        val lines = InkSegmenter.lines(strokes).map { line ->
            val ink = line.strokes.map { strokes[it] }
            readLine(ink)
        }
        return Transcript(lines.filter { it.isNotBlank() })
    }

    /** The best reading of one line over all languages the recognizer has (see [ReadingChooser]). */
    suspend fun readLine(ink: List<InkStrokeSamples>): String {
        val readings = LinkedHashMap<String, TextCandidate?>()
        for (tag in languages) {
            if (recognizer.languages.isNotEmpty() && tag !in recognizer.languages) continue
            readings[tag] = recognizer.recognize(ink, tag).firstOrNull()
        }
        return ReadingChooser.pick(readings, ReadingChooser.direction(ink))?.second?.text?.trim().orEmpty()
    }
}
