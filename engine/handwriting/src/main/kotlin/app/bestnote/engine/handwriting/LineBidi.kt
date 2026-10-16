package app.bestnote.engine.handwriting

import app.bestnote.engine.layout.WritingScript

/**
 * One line of mixed text in VISUAL order (left to right), resolved with the Unicode Bidirectional
 * Algorithm ([java.text.Bidi], on Android too): Arabic and Hebrew right to left, Latin, numbers and
 * maths left to right, brackets paired with what they enclose. Each piece has one direction and one
 * script, so it is written in one font: Arabic and Hebrew in the same sentence never share a run
 * (each gets its own hand), and an English word in a Hebrew line keeps its own order.
 */
internal object LineBidi {
    /**
     * A piece of a line: plain [text] (one script, one direction) or an inline maths [token] (see
     * [InlineMath.encode]). [rtl]: the text reads right to left inside its box.
     */
    data class Piece(val text: String, val token: Int?, val rtl: Boolean, val script: WritingScript?)

    /** Stand-in for a maths token while resolving levels: maths is a left-to-right object. */
    private const val MATH = 'M'

    private val MIRROR = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    /** [line] (words separated by single spaces, maths as tokens) in visual order, left to right. */
    fun visual(line: String, rtl: Boolean): List<Piece> {
        val logical = logical(line, rtl)
        if (logical.isEmpty()) return emptyList()
        val levels = ByteArray(logical.size) { logical[it].second }
        val arr = Array<Any>(logical.size) { logical[it].first }
        java.text.Bidi.reorderVisually(levels, 0, arr, 0, arr.size)
        return arr.map { it as Piece }
    }

    /** Pieces in reading (logical) order with their bidi embedding levels (odd = right to left). */
    fun logical(line: String, rtl: Boolean): List<Pair<Piece, Byte>> {
        val sb = StringBuilder()
        val tokenAt = ArrayList<Int>()
        for ((wi, word) in line.split(' ').withIndex()) {
            if (wi > 0) { sb.append(' '); tokenAt += -1 }
            for ((token, part) in InlineMath.segments(word)) {
                if (token != null) { sb.append(MATH); tokenAt += token } else for (c in part) { sb.append(c); tokenAt += -1 }
            }
        }
        val text = sb.toString()
        if (text.isBlank()) return emptyList()
        val bidi = java.text.Bidi(text, if (rtl) java.text.Bidi.DIRECTION_RIGHT_TO_LEFT else java.text.Bidi.DIRECTION_LEFT_TO_RIGHT)
        val out = ArrayList<Pair<Piece, Byte>>()
        var start = 0
        var segScript: WritingScript? = null
        fun flush(end: Int) {
            if (end <= start) return
            val level = bidi.getLevelAt(start)
            val odd = level % 2 == 1
            val tok = tokenAt[start]
            if (tok >= 0) {
                out += Piece("", tok, false, null) to level.toByte()
            } else {
                var t = text.substring(start, end)
                // Brackets alone in a right-to-left piece: no strong letter tells the renderer to mirror them.
                if (odd && t.none { WritingScript.of(it) != null || it.isDigit() }) t = t.map { MIRROR[it] ?: it }.joinToString("")
                out += Piece(t, null, odd, segScript) to level.toByte()
            }
            start = end
            segScript = null
        }
        for (i in text.indices) {
            val c = text[i]
            val script = WritingScript.of(c) ?: if (c.isDigit()) WritingScript.Latin else null
            val cut = i > start && (
                tokenAt[i] >= 0 || tokenAt[i - 1] >= 0 ||
                    bidi.getLevelAt(i) != bidi.getLevelAt(start) ||
                    (script != null && segScript != null && script != segScript)
                )
            if (cut) flush(i)
            if (script != null && segScript == null) segScript = script
        }
        flush(text.length)
        return out
    }
}
