package app.bestnote.ai.vision.figeval

import app.bestnote.ai.vision.NoteWire
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.figlib.FigSpec
import app.bestnote.engine.layout.figlib.FigureLibrary
import app.bestnote.engine.layout.figlib.Params
import app.bestnote.engine.layout.figlib.Pen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * FIG-LIB generalisation eval, offline part: random ground-truth figures of every kind ([FigGen]),
 * (a) written on the wire the ways models write them and parsed back, (b) rendered and measured
 * against their parameters ([Probes]). Scores go to build/figeval/scores.txt (and into the PR).
 */
class FigEvalTest {
    private val perKind = 12

    private fun specs(): List<FigSpec> {
        val g = FigGen(seed = 20261018)
        return g.kinds.flatMap { k -> (0 until perKind).map { g.of(k) } }
    }

    /** One element as a model might write it: [style] 0 = strings (the schema), 1 = numbers, 2 = an object, 3 = alias + noise. */
    private fun wire(s: FigSpec, style: Int, rnd: Random): String {
        fun v(x: Any, str: Boolean): JsonElement = when {
            x is Number && str -> JsonPrimitive(Params.textOf(x))
            x is Number -> JsonPrimitive(x)
            else -> JsonPrimitive(x.toString())
        }
        val kind = if (style == 3) FigureLibrary.find(s.kind)!!.aliases.let { a -> if (a.isEmpty()) s.kind.uppercase() else a[rnd.nextInt(a.size)].replaceFirstChar(Char::uppercase) } else s.kind
        val p: JsonElement = when (style) {
            2 -> {
                val by = s.rows.groupBy { it[0].toString() }
                JsonObject(by.mapValues { (_, rows) ->
                    if (rows.size == 1 && rows[0].size == 2) v(rows[0][1], false)
                    else if (rows.size == 1) JsonArray(rows[0].drop(1).map { v(it, false) })
                    else JsonArray(rows.map { r -> JsonArray(r.drop(1).map { v(it, false) }) })
                })
            }
            else -> {
                val rows = s.rows.map { r -> JsonArray(r.map { v(it, style == 0 || style == 3) }) }.toMutableList()
                if (style == 3) {
                    rows.add(rnd.nextInt(rows.size + 1), JsonArray(listOf(JsonPrimitive("zz_unknown"), JsonPrimitive("1"))))
                }
                JsonArray(rows)
            }
        }
        val o = JsonObject(mapOf("e" to JsonPrimitive("Diagram"), "box" to JsonArray(listOf(100, 120, 480, 400).map(::JsonPrimitive)), "k" to JsonPrimitive(kind), "p" to p, "c" to JsonPrimitive(0.9)))
        return """{"elements":[$o]}"""
    }

    /** Same drawing: every shape of one within half a unit of the other's (sampled both ways). */
    private fun same(a: app.bestnote.core.model.VectorDiagram, b: app.bestnote.core.model.VectorDiagram): Boolean {
        val sa = a.shapes.filter { it !is VectorShape.Label }.flatMap(Pen::samples)
        val sb = b.shapes.filter { it !is VectorShape.Label }.flatMap(Pen::samples)
        if (sa.isEmpty() || sb.isEmpty()) return sa.size == sb.size
        fun cover(x: List<app.bestnote.core.model.Vec2>, y: List<app.bestnote.core.model.Vec2>) = x.all { p -> y.any { q -> kotlin.math.hypot(p.x - q.x, p.y - q.y) < 0.6f } }
        return cover(sa, sb) && cover(sb, sa) && a.shapes.filterIsInstance<VectorShape.Label>().map { it.text }.toSet() == b.shapes.filterIsInstance<VectorShape.Label>().map { it.text }.toSet()
    }

    @Test fun `parse rate and renderer fidelity on the generalisation set`() {
        val all = specs()
        val rnd = Random(7)
        var parsed = 0
        var tried = 0
        val parseFails = ArrayList<String>()
        for (s in all) for (style in 0..3) {
            tried++
            val doc = NoteWire.parse(wire(s, style, rnd))
            val spec = doc.elements.firstOrNull()?.figure?.spec
            val direct = FigureLibrary.render(s)
            val back = spec?.let(FigureLibrary::render)
            if (spec != null && spec.kind == s.kind && direct != null && back != null && same(direct.diagram, back.diagram)) parsed++ else parseFails += "${s.kind} style $style: ${spec?.rows}"
        }
        var checks = 0
        var passed = 0
        var rendered = 0
        val fails = ArrayList<String>()
        val byKind = LinkedHashMap<String, IntArray>()
        for (s in all) {
            val r = FigureLibrary.render(s)
            val k = byKind.getOrPut(s.kind) { IntArray(2) }
            if (r == null) { fails += "${s.kind}: not rendered ${s.rows}"; k[1]++; checks++; continue }
            rendered++
            for (c in Probes.run(s, r)) {
                checks++; k[1]++
                if (c.ok) { passed++; k[0]++ } else fails += "${c.name}   ${s.rows}"
            }
        }
        val parseRate = parsed.toFloat() / tried
        val fidelity = passed.toFloat() / checks
        val report = buildString {
            appendLine("FIG-LIB generalisation eval (offline), ${all.size} random figures, ${FigureLibrary.elements.size} kinds")
            appendLine("(a) schema parse rate: $parsed/$tried = ${"%.3f".format(parseRate)} (4 wire styles each: strings, numbers, object, alias+noise)")
            appendLine("(b) renderer fidelity: $passed/$checks checks = ${"%.3f".format(fidelity)}; rendered ${rendered}/${all.size}")
            for ((k, v) in byKind) appendLine("    $k: ${v[0]}/${v[1]}")
            if (parseFails.isNotEmpty()) appendLine("parse failures:\n  " + parseFails.take(30).joinToString("\n  "))
            if (fails.isNotEmpty()) appendLine("fidelity failures:\n  " + fails.take(80).joinToString("\n  "))
        }
        File("build/figeval").mkdirs()
        File("build/figeval/scores.txt").writeText(report)
        println(report)
        assertTrue("parse rate $parseRate", parseRate >= 0.98f)
        assertTrue("fidelity $fidelity", fidelity >= 0.95f)
    }
}
