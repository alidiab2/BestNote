package app.bestnote.engine.handwriting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A single-stroke glyph in font units: polylines, y down, baseline at y = [HersheyFont.BASELINE]. */
internal class HGlyph(val strokes: List<Poly>, val width: Float)

/**
 * Hershey single-stroke font (`.jhf`, public domain), lines mapped in order to ASCII 32..126.
 * Port of tools/handwriting/hw/hershey.py.
 */
internal class HersheyFont(text: String, val xHeightUnits: Float) {
    val glyphs: Map<Char, HGlyph>

    init {
        val lines = ArrayList<String>()
        var buf = ""
        for (l in text.lines()) {
            buf += l
            if (buf.length >= 8 && buf.length - 8 >= buf.substring(5, 8).trim().toInt() * 2) {
                lines += buf
                buf = ""
            }
        }
        glyphs = lines.withIndex().associate { (i, line) -> (32 + i).toChar() to parse(line) }
    }

    private fun parse(line: String): HGlyph {
        val count = line.substring(5, 8).trim().toInt()
        val data = line.substring(8)
        val pairs = (0 until count).map { data.substring(it * 2, it * 2 + 2) }
        val left = pairs[0][0] - 'R'
        val right = pairs[0][1] - 'R'
        val strokes = ArrayList<Poly>()
        var cur = ArrayList<P>()
        for (p in pairs.drop(1)) {
            if (p == " R") {
                if (cur.isNotEmpty()) strokes += cur
                cur = ArrayList()
                continue
            }
            cur += P((p[0] - 'R' - left).toFloat(), (p[1] - 'R').toFloat())
        }
        if (cur.isNotEmpty()) strokes += cur
        return HGlyph(strokes, (right - left).toFloat())
    }

    companion object {
        const val BASELINE = 9f
    }
}

/** One contextual form of an Arabic letter: pen paths in em, x from the right edge (<= 0), y down. */
internal class ArabicForm(val advance: Float, val bodies: List<Poly>, val marks: List<Poly>)

/** Skeletonised Aref Ruqaa forms (SIL OFL 1.1) exported by tools/handwriting/export_glyphs.py. */
internal class ArabicGlyphs(json: String) {
    private val forms: Map<String, Map<String, ArabicForm>>
    val dual: Set<Char>
    val right: Set<String>

    init {
        val root = Json.parseToJsonElement(json).jsonObject
        val joining = root.getValue("joining").jsonObject
        dual = joining.getValue("dual").jsonPrimitive.content.toSet()
        val rightStr = joining.getValue("right").jsonPrimitive.content
        right = (rightStr.map { it.toString() } + LIGATURES).toSet()
        forms = root.getValue("glyphs").jsonObject.mapValues { (_, v) ->
            (v as JsonObject).mapValues { (_, f) -> form(f.jsonObject) }
        }
    }

    private fun form(o: JsonObject): ArabicForm {
        fun polys(key: String) = o.getValue(key).jsonArray.map { arr ->
            val v = arr.jsonArray.map { it.jsonPrimitive.float }
            (v.indices step 2).map { P(v[it], v[it + 1]) }
        }
        return ArabicForm(o.getValue("adv").jsonPrimitive.float, polys("bodies"), polys("marks"))
    }

    fun has(unit: String) = forms.containsKey(unit)

    fun form(unit: String, form: String): ArabicForm? {
        val f = forms[unit] ?: return null
        return f[form] ?: f["isol"] ?: f.values.firstOrNull()
    }

    fun joinsNext(unit: String): Boolean = unit.length == 1 && unit[0] in dual

    fun joinsPrev(unit: String): Boolean = (unit.length == 1 && unit[0] in dual) || unit in right

    companion object {
        val LIGATURES = listOf("لا", "لأ", "لإ", "لآ")
    }
}

/** Loads the shipped glyph data once (from the JVM resources of this module). */
internal object GlyphData {
    private fun read(name: String): String =
        requireNotNull(GlyphData::class.java.getResourceAsStream("/handwriting/$name")) { "Missing glyph resource $name" }
            .use { it.readBytes().decodeToString() }

    /** Hershey Roman simplex: the neat print hand (x-height 14 font units). */
    val print: HersheyFont by lazy { HersheyFont(read("futural.jhf"), 14f) }

    /** Hershey script simplex: joined cursive (x-height 9 font units). */
    val cursive: HersheyFont by lazy { HersheyFont(read("scripts.jhf"), 9f) }

    val arabic: ArabicGlyphs by lazy { ArabicGlyphs(read("arabic-ruqaa.json")) }
}
