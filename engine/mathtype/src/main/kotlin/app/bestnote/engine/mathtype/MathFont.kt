package app.bestnote.engine.mathtype

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/** One glyph of the math font, in font units (y up). [path] is its outline; see [GlyphPath]. */
class Glyph(
    val id: Int,
    val advance: Int,
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
    val italic: Int,
    /** Where an accent above this glyph is centered (x); null = half the advance. */
    val topAccent: Int?,
    val path: GlyphPath,
)

/** A size variant of a stretchy glyph: [glyph] measures [advance] along the stretch direction. */
class Variant(val glyph: Int, val advance: Int)

/** A piece of a stretchy assembly; [extender] pieces repeat to fill the length. */
class AssemblyPart(val glyph: Int, val startConnector: Int, val endConnector: Int, val fullAdvance: Int, val extender: Boolean)

class Assembly(val italic: Int, val parts: List<AssemblyPart>)

/**
 * Outline commands of a glyph: [ops] holds one op code per command ([MOVE], [LINE], [QUAD], [CUBIC], [CLOSE])
 * and [coords] the points they take, in font units (y up).
 */
class GlyphPath(val ops: ByteArray, val coords: IntArray) {
    companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val QUAD: Byte = 2
        const val CUBIC: Byte = 3
        const val CLOSE: Byte = 4
        val EMPTY = GlyphPath(ByteArray(0), IntArray(0))

        /** Points each op takes. */
        fun points(op: Byte): Int = when (op) {
            MOVE, LINE -> 1
            QUAD -> 2
            CUBIC -> 3
            else -> 0
        }

        internal fun parse(s: String): GlyphPath {
            if (s == "-") return EMPTY
            val ops = ArrayList<Byte>()
            val coords = ArrayList<Int>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val op: Byte = when (c) {
                    'M' -> MOVE
                    'L' -> LINE
                    'Q' -> QUAD
                    'C' -> CUBIC
                    'Z' -> CLOSE
                    else -> error("bad path command $c")
                }
                ops += op
                i++
                val end = (i until s.length).firstOrNull { s[it].isLetter() } ?: s.length
                if (end > i) s.substring(i, end).trim().split(' ').forEach { coords += it.toInt() }
                i = end
            }
            return GlyphPath(ops.toByteArray(), coords.toIntArray())
        }
    }
}

/**
 * STIX Two Math (SIL OFL 1.1): glyph outlines, metrics and the OpenType MATH tables (constants,
 * italic corrections, accent attachment, size variants and assemblies), loaded once from the
 * module's resources. Pure JVM: the typesetter and its tests run without Android.
 */
class MathFont private constructor(
    val unitsPerEm: Int,
    val minConnectorOverlap: Int,
    val version: String,
    private val constants: Map<String, Int>,
    private val glyphs: Array<Glyph>,
    private val cmap: Map<Int, Int>,
    private val vertical: Map<Int, List<Variant>>,
    private val horizontal: Map<Int, List<Variant>>,
    private val verticalAssembly: Map<Int, Assembly>,
    private val horizontalAssembly: Map<Int, Assembly>,
) {
    /** A MATH constant in font units (percentages for the script scale-downs); 0 when unknown. */
    fun constant(name: String): Int = constants[name] ?: 0

    fun glyph(id: Int): Glyph = glyphs[id]

    /** The glyph for [codePoint], or null when the font has none. */
    fun glyphFor(codePoint: Int): Glyph? = cmap[codePoint]?.let { glyphs[it] }

    fun has(codePoint: Int): Boolean = codePoint in cmap

    fun verticalVariants(glyph: Int): List<Variant> = vertical[glyph].orEmpty()
    fun horizontalVariants(glyph: Int): List<Variant> = horizontal[glyph].orEmpty()
    fun verticalAssembly(glyph: Int): Assembly? = verticalAssembly[glyph]
    fun horizontalAssembly(glyph: Int): Assembly? = horizontalAssembly[glyph]

    val glyphCount: Int get() = glyphs.size

    companion object {
        private const val RESOURCE = "/math/stix-two-math.txt.gz"

        /** The bundled font, parsed on first use (about 300 KB compressed; call off the main thread first). */
        val stixTwo: MathFont by lazy { load() }

        private fun load(): MathFont {
            val stream = MathFont::class.java.getResourceAsStream(RESOURCE) ?: error("missing $RESOURCE")
            BufferedReader(InputStreamReader(GZIPInputStream(stream), Charsets.UTF_8)).use { r -> return parse(r.lineSequence()) }
        }

        @Suppress("CyclomaticComplexMethod")
        private fun parse(lines: Sequence<String>): MathFont {
            var upem = 1000
            var overlap = 0
            var version = ""
            val constants = HashMap<String, Int>()
            val glyphs = ArrayList<Glyph>()
            val cmap = HashMap<Int, Int>()
            val vertical = HashMap<Int, List<Variant>>()
            val horizontal = HashMap<Int, List<Variant>>()
            val vAsm = HashMap<Int, Assembly>()
            val hAsm = HashMap<Int, Assembly>()
            for (line in lines) {
                if (line.isEmpty()) continue
                // A glyph's path (the 10th field) contains spaces itself.
                val f = line.split(' ', limit = 10)
                when (f[0]) {
                    "H" -> { upem = f[1].toInt(); overlap = f[2].toInt(); version = f[3].replace('_', ' ') }
                    "K" -> constants[f[1]] = f[2].toInt()
                    "G" -> glyphs += Glyph(
                        f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f[6].toInt(), f[7].toInt(),
                        f[8].takeIf { it != "-" }?.toInt(), GlyphPath.parse(f[9]),
                    )
                    "U" -> cmap[f[1].toInt(16)] = f[2].toInt()
                    "V", "W" -> {
                        val list = f[2].split(',').map { v -> v.split(':').let { Variant(it[0].toInt(), it[1].toInt()) } }
                        (if (f[0] == "V") vertical else horizontal)[f[1].toInt()] = list
                    }
                    "A", "B" -> {
                        val parts = f[3].split(',').map { p ->
                            p.split(':').let { AssemblyPart(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4] == "1") }
                        }
                        (if (f[0] == "A") vAsm else hAsm)[f[1].toInt()] = Assembly(f[2].toInt(), parts)
                    }
                }
            }
            glyphs.sortBy { it.id }
            return MathFont(upem, overlap, version, constants, glyphs.toTypedArray(), cmap, vertical, horizontal, vAsm, hAsm)
        }
    }
}
