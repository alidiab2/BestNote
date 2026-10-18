package app.bestnote.engine.layout.figlib

/**
 * FIG-LIB: WHAT a board drawing is, as the AI recognised it: a library element [kind] ("incline",
 * "circuit", "plot", …) and its parameters as [rows]. A row is a key followed by its values
 * (`["a", 37]`, `["f", "N", "n"]`, `["R", "R_1", "a", "b"]`); a key may repeat (one force per row).
 * Values are only [String]s and [Double]s, so the same rows travel through every provider's schema
 * (an array of arrays of strings and numbers) and through storage unchanged. [confidence] is the
 * AI's own (0–1, null = not given); a low one lets the photo's own strokes win ([FigureLibrary]).
 */
data class FigSpec(val kind: String, val rows: List<List<Any>>, val confidence: Float? = null) {
    /** The rows as typed accessors. */
    val params: Params get() = Params(rows)

    companion object {
        /** Most rows read from one figure (a model loop is cut, never the page). */
        const val MAX_ROWS = 80

        /** Most values in one row. */
        const val MAX_VALUES = 12

        /** Longest text value kept. */
        const val MAX_TEXT = 40

        /**
         * Rows from loosely typed values (what a JSON reader gives): `[["a",37],…]`, or an object
         * `{"a":37,"f":[["N","n"],["mg","g"]]}` (a list of lists under one key = one row each). Strings
         * that are numbers stay strings (the accessors read them as numbers); nulls, booleans and nested
         * objects are flattened or dropped. Never throws.
         */
        fun rowsOf(raw: Any?): List<List<Any>> {
            val out = ArrayList<List<Any>>()
            fun value(v: Any?): Any? = when (v) {
                null -> null
                is Number -> v.toDouble().takeIf { it.isFinite() }
                is Boolean -> if (v) 1.0 else 0.0
                is String -> v.trim().take(MAX_TEXT).takeIf { it.isNotEmpty() }
                else -> null
            }
            fun row(key: String, values: List<Any?>) {
                val k = key.trim().take(MAX_TEXT)
                if (k.isEmpty() || out.size >= MAX_ROWS) return
                out += listOf<Any>(k) + values.mapNotNull(::value).take(MAX_VALUES)
            }
            when (raw) {
                is List<*> -> for (r in raw) when (r) {
                    is List<*> -> {
                        val k = r.firstOrNull() as? String ?: continue
                        // A nested list after the key ([“pts”, [0,0], [1,2]]) is spread into the row.
                        row(k, r.drop(1).flatMap { if (it is List<*>) it else listOf(it) })
                    }
                    is Map<*, *> -> out.addAll(rowsOf(r))
                    is String -> row(r, emptyList())
                    else -> Unit
                }
                is Map<*, *> -> for ((k, v) in raw) {
                    val key = k as? String ?: continue
                    when {
                        v is List<*> && v.isNotEmpty() && v.all { it is List<*> } -> v.forEach { row(key, (it as List<*>).flatMap { x -> if (x is List<*>) x else listOf(x) }) }
                        v is List<*> -> row(key, v.flatMap { if (it is List<*>) it else listOf(it) })
                        v is Map<*, *> -> Unit
                        else -> row(key, listOf(v))
                    }
                }
                else -> Unit
            }
            return out
        }
    }
}

/**
 * Forgiving, clamped reads of a figure's rows. Every getter has a default and a range, so a model's
 * slip (a string "37°", an angle of 900, a missing row) gives a sane drawing, never a crash.
 */
class Params(val rows: List<List<Any>>) {
    private fun norm(k: String) = k.lowercase().filter { it.isLetterOrDigit() || it == '_' }

    /** All rows whose key is one of [keys] (case and punctuation ignored), values only. */
    fun all(vararg keys: String): List<List<Any>> {
        val ks = keys.map(::norm).toSet()
        return rows.filter { it.isNotEmpty() && norm(it[0].toString()) in ks }.map { it.drop(1) }
    }

    fun first(vararg keys: String): List<Any>? = all(*keys).firstOrNull()

    fun has(vararg keys: String): Boolean = first(*keys) != null

    /** The first number of the first matching row, clamped into [range]. */
    fun num(vararg keys: String, default: Float, range: ClosedFloatingPointRange<Float> = -1e6f..1e6f): Float =
        first(*keys)?.firstNotNullOfOrNull { numOf(it) }?.coerceIn(range.start, range.endInclusive) ?: default

    fun numOrNull(vararg keys: String, range: ClosedFloatingPointRange<Float> = -1e6f..1e6f): Float? =
        first(*keys)?.firstNotNullOfOrNull { numOf(it) }?.coerceIn(range.start, range.endInclusive)

    fun int(vararg keys: String, default: Int, range: IntRange): Int = num(*keys, default = default.toFloat(), range = range.first.toFloat()..range.last.toFloat()).toInt()

    /** The first text of the first matching row (a number is written back as text). */
    fun str(vararg keys: String): String? = first(*keys)?.firstOrNull()?.let { textOf(it) }

    /** A flag: present without a value, or a truthy value ("1", "yes", "y", "true", 1). */
    fun flag(vararg keys: String, default: Boolean = false): Boolean {
        val r = first(*keys) ?: return default
        val v = r.firstOrNull() ?: return true
        return when (v) {
            is Double -> v != 0.0
            else -> v.toString().lowercase() !in setOf("0", "no", "n", "false", "off", "none")
        }
    }

    /** One of [options] (prefix match on the first letters), else [default]. */
    fun choice(vararg keys: String, options: List<String>, default: String): String {
        val v = str(*keys)?.lowercase()?.trim() ?: return default
        return options.firstOrNull { it == v } ?: options.firstOrNull { v.startsWith(it) || it.startsWith(v) } ?: default
    }

    companion object {
        /** "37", "37°", "37deg", "-2.5", "1/2", "π/2" → a number; anything else → null. */
        fun numOf(v: Any?): Float? = when (v) {
            is Number -> v.toFloat().takeIf { it.isFinite() }
            is String -> parseNumber(v)
            else -> null
        }

        fun textOf(v: Any?): String? = when (v) {
            null -> null
            is Double -> if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else v.toString()
            is Number -> textOf(v.toDouble())
            else -> v.toString().trim().takeIf { it.isNotEmpty() }
        }

        private fun parseNumber(s0: String): Float? {
            val s = s0.trim().lowercase().replace("−", "-").replace("°", "").replace("^\\circ", "").replace("^{\\circ}", "")
                .removeSuffix("deg").replace("\\pi", "π").replace("pi", "π").trim()
            s.toFloatOrNull()?.let { return it.takeIf(Float::isFinite) }
            if (s.contains('/')) {
                val (a, b) = s.split('/', limit = 2)
                val x = parseNumber(a) ?: return null
                val y = parseNumber(b) ?: return null
                return if (y == 0f) null else x / y
            }
            if (s.endsWith("π")) {
                val k = s.removeSuffix("π").ifEmpty { "1" }.let { if (it == "-") "-1" else it }.toFloatOrNull() ?: return null
                return (k * Math.PI).toFloat()
            }
            return null
        }
    }
}
