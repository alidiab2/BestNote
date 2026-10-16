package app.bestnote.core.fonts

import java.nio.ByteBuffer
import java.nio.charset.Charset

/** Why a font file was refused. [reason] is for the log; the UI shows a plain message per [kind]. */
class FontRejected(val kind: Kind, val reason: String) : Exception(reason) {
    enum class Kind { NotAFont, Collection, TooLarge, Damaged, NoLetters }
}

/** What a valid font file offers: its name, the scripts it fully covers, how many glyphs. */
data class FontInfo(val familyName: String?, val scripts: Set<FontScript>, val glyphCount: Int)

/**
 * Checks a .ttf / .otf file without Android: sfnt header and tables, then its `cmap` for the letters of
 * each script. A font that covers no script's letters is refused (it would draw "?" or nothing). The
 * device also loads it as a typeface before it is kept (see [FontRegistry]).
 */
object FontValidator {
    const val MAX_BYTES = 20 * 1024 * 1024

    fun inspect(bytes: ByteArray): FontInfo {
        if (bytes.size > MAX_BYTES) throw FontRejected(FontRejected.Kind.TooLarge, "${bytes.size} bytes")
        if (bytes.size < 12) throw FontRejected(FontRejected.Kind.NotAFont, "too short")
        val b = ByteBuffer.wrap(bytes) // big endian
        val tag = b.getInt(0)
        when (tag) {
            0x00010000, 0x4F54544F /* OTTO */, 0x74727565 /* true */ -> Unit
            0x74746366 /* ttcf */ -> throw FontRejected(FontRejected.Kind.Collection, "font collection")
            else -> throw FontRejected(FontRejected.Kind.NotAFont, "bad signature %08x".format(tag))
        }
        val tables = HashMap<String, IntArray>() // tag -> [offset, length]
        try {
            val n = b.getShort(4).toInt() and 0xFFFF
            if (n == 0 || n > 200) throw FontRejected(FontRejected.Kind.Damaged, "table count $n")
            for (i in 0 until n) {
                val rec = 12 + i * 16
                val name = String(bytes, rec, 4, Charsets.ISO_8859_1)
                val off = b.getInt(rec + 8)
                val len = b.getInt(rec + 12)
                if (off < 0 || len < 0 || off.toLong() + len > bytes.size) throw FontRejected(FontRejected.Kind.Damaged, "table $name out of range")
                tables[name] = intArrayOf(off, len)
            }
            if ("glyf" !in tables && "CFF " !in tables && "CFF2" !in tables) throw FontRejected(FontRejected.Kind.Damaged, "no outlines")
            val cmap = tables["cmap"] ?: throw FontRejected(FontRejected.Kind.Damaged, "no cmap")
            val covered = CodepointSet()
            readCmap(b, cmap[0], cmap[1], covered)
            val scripts = FontScript.entries.filter { s -> s.probe.all { covered.has(it) } }.toSet()
            if (scripts.isEmpty()) throw FontRejected(FontRejected.Kind.NoLetters, "no Latin, Arabic or Hebrew letters")
            val glyphs = tables["maxp"]?.let { b.getShort(it[0] + 4).toInt() and 0xFFFF } ?: 0
            return FontInfo(familyName(b, tables["name"]), scripts, glyphs)
        } catch (e: IndexOutOfBoundsException) {
            throw FontRejected(FontRejected.Kind.Damaged, "truncated")
        } catch (e: java.nio.BufferUnderflowException) {
            throw FontRejected(FontRejected.Kind.Damaged, "truncated")
        }
    }

    /** True when [info] covers every letter of [script]'s probe. */
    fun covers(info: FontInfo, script: FontScript) = script in info.scripts

    private class CodepointSet {
        private val ranges = ArrayList<IntArray>()
        fun add(from: Int, to: Int) { ranges += intArrayOf(from, to) }
        fun has(c: Int) = ranges.any { c >= it[0] && c <= it[1] }
    }

    /** Formats 4 (BMP) and 12 (full) cover practically every font; the others are ignored. */
    private fun readCmap(b: ByteBuffer, base: Int, length: Int, out: CodepointSet) {
        val count = b.getShort(base + 2).toInt() and 0xFFFF
        for (i in 0 until count.coerceAtMost(64)) {
            val sub = base + b.getInt(base + 4 + i * 8 + 4)
            if (sub < base || sub >= base + length) continue
            when (b.getShort(sub).toInt() and 0xFFFF) {
                4 -> {
                    val segX2 = b.getShort(sub + 6).toInt() and 0xFFFF
                    val endBase = sub + 14
                    val startBase = endBase + segX2 + 2
                    val deltaBase = startBase + segX2
                    val rangeBase = deltaBase + segX2
                    for (s in 0 until segX2 / 2) {
                        val end = b.getShort(endBase + s * 2).toInt() and 0xFFFF
                        val start = b.getShort(startBase + s * 2).toInt() and 0xFFFF
                        if (start > end || start == 0xFFFF) continue
                        val delta = b.getShort(deltaBase + s * 2).toInt()
                        val ro = b.getShort(rangeBase + s * 2).toInt() and 0xFFFF
                        // A segment mapping to glyph 0 (notdef) does not cover its characters.
                        if (ro == 0) {
                            if (((start + delta) and 0xFFFF) != 0 || start != end) out.add(start, end)
                        } else {
                            // Sparse segments: record only characters whose glyph index is non-zero.
                            for (c in start..end) {
                                val gAddr = rangeBase + s * 2 + ro + (c - start) * 2
                                if (gAddr + 2 > b.limit()) break
                                val g = b.getShort(gAddr).toInt() and 0xFFFF
                                if (g != 0) out.add(c, c)
                            }
                        }
                    }
                }
                12 -> {
                    val groups = b.getInt(sub + 12)
                    for (g in 0 until groups.coerceIn(0, 100_000)) {
                        val at = sub + 16 + g * 12
                        val start = b.getInt(at)
                        val end = b.getInt(at + 4)
                        val gid = b.getInt(at + 8)
                        if (gid != 0 && start <= end) out.add(start, end)
                    }
                }
            }
        }
    }

    /** The family name (name id 16, else 1), preferring Windows Unicode, else Mac Roman. */
    private fun familyName(b: ByteBuffer, name: IntArray?): String? {
        name ?: return null
        val base = name[0]
        val count = b.getShort(base + 2).toInt() and 0xFFFF
        val strings = base + (b.getShort(base + 4).toInt() and 0xFFFF)
        var best: String? = null
        var bestRank = -1
        for (i in 0 until count.coerceAtMost(500)) {
            val r = base + 6 + i * 12
            val platform = b.getShort(r).toInt() and 0xFFFF
            val id = b.getShort(r + 6).toInt() and 0xFFFF
            if (id != 1 && id != 16) continue
            val len = b.getShort(r + 8).toInt() and 0xFFFF
            val off = b.getShort(r + 10).toInt() and 0xFFFF
            if (strings + off + len > b.limit()) continue
            val raw = ByteArray(len).also { b.get(strings + off, it) }
            val text = when (platform) {
                0, 3 -> String(raw, Charsets.UTF_16BE)
                1 -> String(raw, Charset.forName("x-MacRoman"))
                else -> continue
            }.trim()
            if (text.isEmpty()) continue
            val rank = (if (id == 16) 2 else 0) + (if (platform == 3) 1 else 0)
            if (rank > bestRank) { best = text; bestRank = rank }
        }
        return best
    }
}
