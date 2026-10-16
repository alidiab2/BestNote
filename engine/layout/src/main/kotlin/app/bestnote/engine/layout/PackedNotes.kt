package app.bestnote.engine.layout

/**
 * Tidying the packed layout does on top of [NoteNormalizer] (layout only, never the content):
 * short consecutive equations share one line the way a student writes "a = 1,  b = −1", both as
 * Formula elements and as maths-only solution steps.
 */
object PackedNotes {
    /** At most this many equations on one shared line. */
    const val MAX_SHARED = 3

    /** Equations up to this many characters (and nothing tall) may share a line. */
    const val SHORT_CHARS = 16

    private val TALL = Regex("""\\(int|iint|oint|sum|prod|lim|sqrt|begin|binom|over|left|matrix|cases)(?![a-zA-Z])|\\\\""")

    fun isShortEquation(tex: String?): Boolean {
        val t = tex?.trim() ?: return false
        // One small fraction may share a line ("∞/∞,  0/0"); nested ones may not.
        val fracs = Regex("""\\d?t?frac""").findAll(t).count()
        return t.isNotEmpty() && t.length <= SHORT_CHARS + 6 * fracs && fracs <= 1 && !TALL.containsMatchIn(t) && t.count { it == '=' } <= 1
    }

    fun tidy(doc: NoteDocument): NoteDocument = doc.copy(elements = shareLines(doc.elements))

    private fun join(parts: List<String>) = parts.joinToString(",\\quad ") { it.trim() }

    internal fun shareLines(blocks: List<NoteBlock>): List<NoteBlock> {
        val out = ArrayList<NoteBlock>(blocks.size)
        val newIndex = IntArray(blocks.size)
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            newIndex[i] = out.size
            if (b.element == NoteElement.Formula && isShortEquation(b.math)) {
                val group = arrayListOf(b.math!!)
                var j = i + 1
                while (j < blocks.size && group.size < MAX_SHARED && blocks[j].element == NoteElement.Formula && isShortEquation(blocks[j].math) && !targeted(blocks, j)) {
                    group += blocks[j].math!!
                    newIndex[j] = out.size
                    j++
                }
                out += b.copy(math = join(group))
                i = j
                continue
            }
            out += when (b.element) {
                NoteElement.StepSolution, NoteElement.Example -> b.copy(steps = shareSteps(b.steps))
                NoteElement.Columns -> b.copy(columns = b.columns.map(::shareLines))
                else -> b
            }
            i++
        }
        return out.map { blk -> blk.target?.let { t -> blk.copy(target = newIndex.getOrNull(t)) } ?: blk }
    }

    /** A margin remark points at element [j]: keep it its own element. */
    private fun targeted(blocks: List<NoteBlock>, j: Int) = blocks.any { it.target == j }

    /** Maths-only steps without a reason, each short, written on one line. */
    internal fun shareSteps(steps: List<NoteStep>): List<NoteStep> {
        val out = ArrayList<NoteStep>(steps.size)
        var i = 0
        fun bare(s: NoteStep) = s.text.isNullOrBlank() && s.why.isNullOrBlank() && isShortEquation(s.math)
        while (i < steps.size) {
            val s = steps[i]
            if (!bare(s)) { out += s; i++; continue }
            val group = arrayListOf(s.math!!)
            var j = i + 1
            while (j < steps.size && group.size < MAX_SHARED && bare(steps[j])) { group += steps[j].math!!; j++ }
            out += s.copy(math = join(group))
            i = j
        }
        return out
    }
}
