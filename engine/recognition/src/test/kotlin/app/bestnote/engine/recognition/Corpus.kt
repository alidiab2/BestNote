package app.bestnote.engine.recognition

import app.bestnote.core.model.StylusSample
import java.io.File

/** Reads stroke files from the handwriting corpus (tools/handwriting/corpus), converted from mm to page points. */
object Corpus {
    private const val PT_PER_MM = 72f / 25.4f
    private val stroke = Regex("\"type\":\"(ink|hover)\",\"button\":(?:true|false),\"samples\":\\[(.*?)]}")
    private val sample = Regex(
        "\\{\"x\":([-0-9.eE]+),\"y\":([-0-9.eE]+),\"t_ms\":([-0-9.eE]+),\"pressure\":([-0-9.eE]+)," +
            "\"tiltRad\":([-0-9.eE]+),\"orientationRad\":([-0-9.eE]+)}",
    )

    private val dir: File by lazy {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "tools/handwriting/corpus").isDirectory) d = d.parentFile
        File(requireNotNull(d) { "tools/handwriting/corpus not found" }, "tools/handwriting/corpus")
    }

    /** The ink strokes of corpus file [name] (without .json), in drawing order. */
    fun strokes(name: String): List<List<StylusSample>> {
        val text = File(dir, "$name.json").readText()
        return stroke.findAll(text).filter { it.groupValues[1] == "ink" }.map { m ->
            sample.findAll(m.groupValues[2]).map { s ->
                val v = s.groupValues
                StylusSample(
                    v[1].toFloat() * PT_PER_MM, v[2].toFloat() * PT_PER_MM, v[4].toFloat(), v[5].toFloat(), v[6].toFloat(),
                    v[3].toDouble().toLong(),
                )
            }.toList()
        }.toList()
    }
}
