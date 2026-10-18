package app.bestnote.ai.vision.figeval

import app.bestnote.ai.vision.Canary
import app.bestnote.ai.vision.NoteWire
import app.bestnote.engine.layout.FigureMath
import app.bestnote.engine.layout.figlib.FigSpec
import app.bestnote.engine.layout.figlib.FigureLibrary
import app.bestnote.engine.layout.figlib.Params
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * FIG-LIB generalisation eval, live part (c): Claude's recorded answers for the synthetic boards
 * ([FigBoardsTest], `ai-live.sh … provider=claude`) scored against their ground truth: is each
 * drawing found (box overlap), is its kind right, are its key parameters and labels right, and does
 * the answer render. No call is made here.
 */
class LiveFigScoreTest {
    private fun res(name: String) = javaClass.classLoader.getResource("figeval/$name")?.readText()

    private fun answerText(raw: String): String {
        val o = Json.parseToJsonElement(raw).jsonObject
        return o["content"]!!.jsonArray.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "text" }["text"]!!.jsonPrimitive.content
    }

    private fun norm(s: String) = s.replace("\\vec", "").replace("\\", "").replace("{", "").replace("}", "").replace(" ", "").replace("^circ", "°").lowercase()

    /** The kinds a teacher's drawing may fairly be read as (a right triangle IS a geometry figure). */
    private val same = listOf(setOf("geo", "triangle"), setOf("fbd", "blocks"), setOf("plot", "numberline"))

    private fun kindOk(gt: String, ai: String?) = ai == gt || same.any { gt in it && ai in it }

    /** Key parameters, kind by kind: 1 = right, 0 = wrong. */
    private fun params(gt: FigSpec, ai: FigSpec): List<Int> {
        val g = gt.params
        val a = ai.params
        fun b(x: Boolean) = if (x) 1 else 0
        fun numNear(key: String, tol: Float) = g.numOrNull(key)?.let { v -> b(a.numOrNull(key)?.let { abs(it - v) <= tol } == true) }
        fun choice(key: String) = g.str(key)?.let { v -> b(a.str(key)?.lowercase() == v.lowercase()) }
        return listOfNotNull(
            when (gt.kind) {
                "incline", "pendulum" -> numNear("a", 5f)
                "refraction" -> numNear("i", 8f)
                "lens", "mirror", "solid", "wave", "pulley", "spring" -> choice("type") ?: choice("state")
                "venn" -> b(a.first("sets")?.size == g.first("sets")?.size)
                "circuit" -> b(a.rows.count { it.size >= 3 && it[0] != "I" } == g.rows.count { it.size >= 3 && it[0] != "I" })
                "fbd", "blocks" -> b(a.all("f").size == g.all("f").size)
                "vectors" -> b(a.all("v").size == g.all("v").size)
                "ptree" -> b(a.all("b").size == g.all("b").size)
                "sketch" -> b(a.all("box").size == g.all("box").size)
                "geo" -> b(a.all("pt").size == g.all("pt").size)
                "triangle" -> choice("type")
                "numberline" -> g.first("int")?.let { iv -> b(a.first("int")?.let { Params.numOf(it[0]) == Params.numOf(iv[0]) } == true) }
                "plot" -> {
                    val fg = FigureMath.parse(g.str("f")!!)!!
                    val fa = a.str("f")?.let(FigureMath::parse)
                    b(fa != null && listOf(-1.5, 0.5, 1.0, 2.0).all { x -> val u = fg(x); val v = fa(x); !u.isFinite() || abs(u - v) < 0.05 * max(1.0, abs(u)) })
                }
                else -> null
            },
            if (gt.kind == "incline") choice("up") else null,
        )
    }

    @Test fun `recorded Claude answers on the synthetic boards`() {
        var gts = 0
        var found = 0
        var kindRight = 0
        var paramRight = 0
        var paramTotal = 0
        var labelHit = 0
        var labelTotal = 0
        var renders = 0
        var withSpec = 0
        val kinds = HashSet<String>()
        val notes = ArrayList<String>()
        val strip = Canary.stripHeight(1600, 1100)
        val yScale = (1100f + strip) / 1100f
        for (i in 0 until FigBoardsTest.BOARDS) {
            val raw = res("live-board-$i.txt") ?: continue
            val gt = Json.parseToJsonElement(res("board-$i.gt.json")!!).jsonArray.map { it.jsonObject }
            val doc = NoteWire.parse(answerText(raw))
            val figs = doc.flatten().mapNotNull { it.figure }.toMutableList()
            for (g in gt) {
                gts++
                val gk = g["k"]!!.jsonPrimitive.content
                kinds += gk
                val gb = g["box"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() / 1000f }
                val gspec = FigSpec(gk, FigSpec.rowsOf(plain(g["p"]!!)))
                fun iou(f: app.bestnote.engine.layout.BoardFigure): Float {
                    val b = f.box ?: return 0f
                    val t = b.top * yScale
                    val bt = b.bottom * yScale
                    val ix = min(b.right, gb[2]) - max(b.left, gb[0])
                    val iy = min(bt, gb[3]) - max(t, gb[1])
                    if (ix <= 0 || iy <= 0) return 0f
                    val inter = ix * iy
                    return inter / ((b.right - b.left) * (bt - t) + (gb[2] - gb[0]) * (gb[3] - gb[1]) - inter)
                }
                val best = figs.maxByOrNull(::iou)
                if (best == null || iou(best) < 0.2f) { notes += "board $i: $gk not found"; continue }
                figs.remove(best)
                found++
                val spec = best.spec
                if (spec != null) withSpec++
                if (spec != null && FigureLibrary.render(spec) != null) renders++
                if (!kindOk(gk, spec?.kind)) { notes += "board $i: $gk read as ${spec?.kind ?: best.kind}"; continue }
                kindRight++
                // Read as an equivalent kind (a triangle as a geometry figure): its rows are another kind's, so only the kind counts.
                val ps = if (spec!!.kind == gk) params(gspec, spec) else emptyList()
                paramTotal += ps.size
                paramRight += ps.sum()
                if (ps.any { it == 0 }) notes += "board $i: $gk params ${gspec.rows} vs ${spec.rows}"
                val want = Probes.expectedLabels(gspec).map(::norm).toSet()
                val got = spec.rows.flatMap { r -> r.mapNotNull { v -> (v as? String)?.let(::norm) } }.toSet() + best.labels.map { norm(it.text) }
                labelTotal += want.size
                labelHit += want.count { it in got }
            }
        }
        if (gts == 0) return
        val report = buildString {
            appendLine("(c) live recognition (Claude, ${FigBoardsTest.BOARDS} synthetic boards, $gts figures, ${kinds.size} kinds):")
            appendLine("    found ${found}/$gts; kind right ${kindRight}/$gts = ${"%.2f".format(kindRight.toFloat() / gts)}; key params ${paramRight}/$paramTotal; labels ${labelHit}/$labelTotal; answers that render ${renders}/$found")
            notes.forEach { appendLine("    $it") }
        }
        File("build/figeval").mkdirs()
        File("build/figeval/live-scores.txt").writeText(report)
        println(report)
        assertTrue("kind accuracy", kindRight >= gts * 0.7)
    }

    private fun plain(el: kotlinx.serialization.json.JsonElement): Any? = when (el) {
        is JsonPrimitive -> if (el.isString) el.content else el.content.toDoubleOrNull()
        is JsonArray -> el.map(::plain)
        is JsonObject -> el.mapValues { plain(it.value) }
        else -> null
    }
}
