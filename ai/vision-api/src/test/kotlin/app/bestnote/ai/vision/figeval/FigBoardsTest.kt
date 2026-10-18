package app.bestnote.ai.vision.figeval

import app.bestnote.engine.layout.figlib.FigSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * FIG-LIB evaluation boards for the live recognition score (c): five synthetic whiteboard photos,
 * six figures each, every kind at least once, written with their ground truth to
 * build/figeval/boards/ (`board-N.jpg`, `board-N.json`). Deterministic: the same seed makes the
 * same boards, so recorded answers stay comparable.
 */
class FigBoardsTest {
    companion object {
        const val BOARDS = 5
        val TITLES = listOf("Lesson 4 — review", "שיעור 7 — חזרה", "الدرس ٣ — مراجعة", "Exam prep", "תרגול")

        /** The ground-truth specs per board (deterministic). */
        fun plan(): List<List<FigSpec>> {
            val g = FigGen(seed = 4242)
            val order = g.stage1.shuffled(kotlin.random.Random(99))
            val extra = (0 until BOARDS * 6 - order.size).map { order[it % order.size] }
            return (order + extra).chunked(6).map { ks -> ks.map(g::of) }
        }

        fun gtJson(placed: List<BoardSynth.Placed>): String = JsonArray(
            placed.map { p ->
                JsonObject(mapOf(
                    "k" to JsonPrimitive(p.spec.kind),
                    "box" to JsonArray(p.box.map(::JsonPrimitive)),
                    "p" to JsonArray(p.spec.rows.map { r -> JsonArray(r.map { v -> if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }) }),
                ))
            },
        ).toString()
    }

    @Test fun `evaluation boards are generated with their ground truth`() {
        val dir = File("build/figeval/boards").apply { mkdirs() }
        val plans = plan()
        assertEquals(BOARDS, plans.size)
        for ((i, specs) in plans.withIndex()) {
            val placed = BoardSynth(seed = 500 + i).board(specs, File(dir, "board-$i.jpg"), TITLES[i])
            assertEquals(specs.size, placed.size)
            File(dir, "board-$i.json").writeText(gtJson(placed))
        }
    }
    /** Boards of the chemistry, biology and CS kinds, ready for a live run (`ai-live.sh build/figeval/boards2/board-N.jpg provider=claude`). */
    @Test fun `stage two boards are generated with their ground truth`() {
        val dir = File("build/figeval/boards2").apply { mkdirs() }
        val g = FigGen(seed = 4343)
        val kinds = g.kinds.drop(g.stage1.size)
        val plans = (kinds + kinds.take(18 - kinds.size)).chunked(6).map { ks -> ks.map(g::of) }
        for ((i, specs) in plans.withIndex()) {
            val placed = BoardSynth(seed = 700 + i).board(specs, File(dir, "board-$i.jpg"), TITLES[(i + 1) % TITLES.size])
            assertEquals(specs.size, placed.size)
            File(dir, "board-$i.json").writeText(gtJson(placed))
        }
    }
}
