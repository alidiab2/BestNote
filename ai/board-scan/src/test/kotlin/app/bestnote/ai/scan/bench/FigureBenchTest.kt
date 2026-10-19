package app.bestnote.ai.scan.bench

import app.bestnote.ai.scan.Raster
import app.bestnote.ai.scan.figure.FigureFit
import app.bestnote.ai.scan.figure.FigureRedraw
import app.bestnote.engine.layout.NoteDocument
import org.junit.Test
import java.io.File

/**
 * REQ-001 bench runs: every experiment switch on top of the previous ones, scored on four pipelines
 * (build/bench/report.md, pictures in build/bench/<run>/). Not a gate: the regression thresholds live
 * in FigureFidelityTest.
 */
class FigureBenchTest {
    private fun strip(doc: NoteDocument) = doc.copy(elements = doc.elements.map { b -> b.figure?.let { b.copy(figure = it.copy(spec = null)) } ?: b })

    private class Summary(val f1: Double, val fine: Double, val labels: Float, val extra: Int, val scores: List<FigureBench.Score>) {
        val kinks = scores.sumOf { it.kinks }
        val placed = scores.sumOf { it.placed }.toFloat() / scores.sumOf { it.labelsTotal }
        override fun toString() = "%.3f / %.3f / %d / %.2f / %.2f / %d".format(f1, fine, kinks, labels, placed, extra)
    }

    private fun summary(scores: List<FigureBench.Score>) = Summary(
        scores.map { it.f1 }.average(), scores.map { it.fineF1 }.average(),
        scores.sumOf { it.labelsFound }.toFloat() / scores.sumOf { it.labelsTotal }, scores.sumOf { it.extraLabels }, scores,
    )

    private fun live(tag: String, answers: List<String>, edit: (NoteDocument) -> NoteDocument = { it }, resolve: (NoteDocument, Raster, Float) -> NoteDocument = { d, r, k -> FigureRedraw.resolve(d, r, k) }): Summary {
        val drawn = answers.flatMap { FigureBench.live(it, resolve, edit).entries }.associate { it.key to it.value }
        return summary(FigureBench.truths.filter { it.id in drawn }.map { FigureBench.score(it, drawn[it.id], tag) })
    }

    /** The experiments, each on top of the ones before it ("baseline" = all off = the code before REQ-001 round 2). */
    private val experiments: List<Pair<String, () -> Unit>> = listOf(
        "baseline" to { FigureFit.MIXED_RUNS = false; FigureRedraw.PIXELS_GUARD = false; FigureRedraw.PHOTO_FIRST = false; FigureRedraw.PAD_LONG = false; FigureRedraw.LABEL_SNAP = false; FigureFit.HEAD_AS_DRAWN = false; FigureFit.TWO_SIDED_HEADS = false },
        "E1 pixel-reading guard" to { FigureRedraw.PIXELS_GUARD = true },
        "E2 photo first, library fallback" to { FigureRedraw.PHOTO_FIRST = true },
        "E3 corner-split line/curve runs" to { FigureFit.MIXED_RUNS = true },
        "E4 crop padded by the long side" to { FigureRedraw.PAD_LONG = true },
        "E5 labels on a drawn line snap to their word" to { FigureRedraw.LABEL_SNAP = true },
        "E6 arrowheads as drawn, two-sided on long lines" to { FigureFit.HEAD_AS_DRAWN = true; FigureFit.TWO_SIDED_HEADS = true },
    )

    @Test fun bench() {
        val report = StringBuilder("# REQ-001 figure bench\n\nEach cell: mean f1 / fine f1 / false corners (kinks) / labels found / labels placed where written / extra labels.\n\n")
        report.append("| experiment | oracle (truth boxes) | app, Sonnet answer | app, Opus answer | redraw, Opus boxes | library, Opus params |\n|---|---|---|---|---|---|\n")
        var last: Map<String, Summary> = emptyMap()
        for ((name, setup) in experiments) {
            setup()
            val dir = name.substringBefore(' ')
            val runs = linkedMapOf(
                "oracle" to summary(FigureBench.truths.map { FigureBench.score(it, FigureBench.oracle(it), "$dir/oracle") }),
                "sonnet" to live("$dir/sonnet", FigureBench.sonnet),
                "opus" to live("$dir/opus", FigureBench.opus),
                "redraw" to live("$dir/redraw", FigureBench.opus, ::strip),
                "library" to live("$dir/library", FigureBench.opus, resolve = { d, _, k -> FigureRedraw.resolve(d, null, k) }),
            )
            report.append("| $name | ${runs.values.joinToString(" | ")} |\n")
            println("== $name: " + runs.entries.joinToString("  ") { "${it.key} ${it.value}" })
            last = runs
        }
        report.append("\n## Last experiment, per drawing\n")
        for ((run, s) in last) {
            report.append("\n### $run\n\n| drawing | source | f1 | fine f1 | kinks | recall | precision | labels | placed | extra |\n|---|---|---|---|---|---|---|---|---|---|\n")
            for (x in s.scores) report.append("| ${x.id} | ${x.source} | %.2f | %.2f | ${x.kinks} | %.2f | %.2f | ${x.labelsFound}/${x.labelsTotal} | ${x.placed} | ${x.extraLabels} |\n".format(x.f1, x.fineF1, x.recall, x.precision))
            println("-- $run"); s.scores.forEach { println("  $it") }
        }
        File("build/bench").mkdirs()
        File("build/bench/report.md").writeText(report.toString())
    }
}
