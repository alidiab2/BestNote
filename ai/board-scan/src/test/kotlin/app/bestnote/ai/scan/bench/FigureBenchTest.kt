package app.bestnote.ai.scan.bench

import app.bestnote.ai.scan.figure.FigureFit
import app.bestnote.ai.scan.figure.FigureRedraw
import org.junit.Test
import java.io.File

/**
 * REQ-001 bench runs: every experiment switch on top of the previous ones, scored on three pipelines
 * (build/bench/report.md, pictures in build/bench/<run>/). Not a gate: the regression thresholds live
 * in FigureFidelityTest. Round 2's experiments (E1–E6) are recorded in docs/research/board-figures.md;
 * their switches stay on here.
 */
class FigureBenchTest {
    private class Summary(val scores: List<FigureBench.Score>) {
        val f1 = scores.map { it.f1 }.average()
        val fine = scores.map { it.fineF1 }.average()
        val kinks = scores.sumOf { it.kinks }
        val labels = scores.sumOf { it.labelsFound }.toFloat() / scores.sumOf { it.labelsTotal }
        val placed = scores.sumOf { it.placed }.toFloat() / scores.sumOf { it.labelsTotal }
        val extra = scores.sumOf { it.extraLabels }
        val structure = scores.sumOf { it.structure }
        override fun toString() = "%.3f / %.3f / %d / %d / %.2f / %.2f / %d".format(f1, fine, kinks, structure, labels, placed, extra)
    }

    private fun live(tag: String, answers: List<String>): Summary {
        val drawn = answers.flatMap { FigureBench.live(it).entries }.associate { it.key to it.value }
        return Summary(FigureBench.truths.filter { it.id in drawn }.map { FigureBench.score(it, drawn[it.id], tag) })
    }

    /** Round 3, each on top of the ones before it. */
    private val experiments: List<Pair<String, () -> Unit>> = listOf(
        "R2 on the bench board (main)" to {
            FigureBench.pixels = FigureBench.Pixels.Native
            FigureFit.CUT_LINES = false; FigureFit.KIND_HINTS = false; FigureFit.MASS_BLOCKS = false
        },
        "R2 on a half-size board (today: redraw from the 1600 px copy)" to { FigureBench.pixels = FigureBench.Pixels.Half },
        "F1 each figure cut from the full-resolution photo" to { FigureBench.pixels = FigureBench.Pixels.HalfWithPhoto },
        "K2 a line cut by letters stays one line" to { FigureFit.CUT_LINES = true },
        "K3 the drawing's kind steers the fit" to { FigureFit.KIND_HINTS = true },
        "K4 every mass is a block, rebuilt from the lines around it" to { FigureFit.MASS_BLOCKS = true },
        "R3 (all of it) on a half-size board, without the photo" to { FigureBench.pixels = FigureBench.Pixels.Half },
        "R3 (all of it) on the bench board" to { FigureBench.pixels = FigureBench.Pixels.Native },
    )

    @Test fun bench() {
        val report = StringBuilder("# REQ-001 figure bench (round 3)\n\nEach cell: mean f1 / fine f1 / false corners / structure errors / labels found / labels placed / extra labels.\n\n")
        report.append("| experiment | oracle (truth boxes) | app, Sonnet answer | app, Opus answer |\n|---|---|---|---|\n")
        var last: Map<String, Summary> = emptyMap()
        for ((name, setup) in experiments) {
            setup()
            val dir = name.substringBefore(' ')
            val runs = linkedMapOf(
                "oracle" to Summary(FigureBench.truths.map { FigureBench.score(it, FigureBench.oracle(it), "$dir/oracle") }),
                "sonnet" to live("$dir/sonnet", FigureBench.sonnet),
                "opus" to live("$dir/opus", FigureBench.opus),
            )
            report.append("| $name | ${runs.values.joinToString(" | ")} |\n")
            println("== $name: " + runs.entries.joinToString("  ") { "${it.key} ${it.value}" })
            last = runs
        }
        report.append("\n## Last experiment, per drawing\n")
        for ((run, s) in last) {
            report.append("\n### $run\n\n| drawing | source | f1 | fine f1 | kinks | structure | recall | precision | labels | placed | extra |\n|---|---|---|---|---|---|---|---|---|---|---|\n")
            for (x in s.scores) report.append("| ${x.id} | ${x.source} | %.2f | %.2f | ${x.kinks} | ${x.structure} | %.2f | %.2f | ${x.labelsFound}/${x.labelsTotal} | ${x.placed} | ${x.extraLabels} |\n".format(x.f1, x.fineF1, x.recall, x.precision))
            println("-- $run"); s.scores.forEach { println("  $it") }
        }
        FigureBench.pixels = FigureBench.Pixels.Native
        File("build/bench").mkdirs()
        File("build/bench/report.md").writeText(report.toString())
    }
}
