package app.bestnote.engine.layout

/**
 * What the app enforces on every AI [NoteDocument] before layout, whatever the model wrote:
 * 1. **Maths out of right-to-left sentences** ([mathOutOfRtl]): inside Hebrew or Arabic text only
 *    short atoms stay inline (`x`, `e^x`, `30!`, `V_0`); a fraction, limit, sum, root or any
 *    multi-term expression is moved to its own centred Formula line right after the sentence, so
 *    bidi never jumbles it and it never overlaps the lines around it.
 * 2. **Only the template's elements** ([allowedOnly]): anything else becomes the closest allowed one.
 * 3. **Formula numbers** ([number]): NumberedFormula and Derivation results are numbered ①②… in
 *    order (continuing from [startNumber]) unless the model gave a number.
 * 4. **Glossary** ([withGlossary]): the page's terms that the notebook glossary does not have yet are
 *    written in one Glossary element at the end (when the template allows Glossary).
 */
object NoteNormalizer {
    fun normalize(
        doc: NoteDocument,
        template: NoteTemplate,
        glossary: NotebookGlossary = NotebookGlossary(),
        startNumber: Int = 1,
    ): NoteDocument {
        var d = allowedOnly(doc, template.elements)
        d = mathOutOfRtl(d)
        d = number(d, startNumber)
        if (template.ai.glossary) d = withGlossary(d, glossary, NoteElement.Glossary in template.elements)
        return d
    }

    // ------------------------------------------------------------------ 1. maths in RTL text

    private val INLINE = Regex("""\\\((.+?)\\\)|\$(.+?)\$""")
    private val BIG = Regex("""\\(d?t?frac|lim|sum|int|iint|oint|prod|sqrt|binom|over|begin|left|right|matrix|cases|underbrace|overbrace)(?![a-zA-Z])""")
    private val RELATION = Regex("""[=+<>/]|\\(le|leq|ge|geq|neq|ne|approx|cdot|times|pm|mp|to|Rightarrow|rightarrow|Leftrightarrow|equiv|sim|propto|div)(?![a-zA-Z])""")

    /** A single symbol, number or tiny expression that bidi cannot scramble: `x`, `e^x`, `30!`, `V_0`, `\Delta t`, `-3`. */
    fun isShortAtom(tex: String): Boolean {
        val t = tex.trim()
        if (t.isEmpty() || t.length > ATOM_CHARS) return false
        if (BIG.containsMatchIn(t) || RELATION.containsMatchIn(t)) return false
        // A minus is fine only as the sign of a number ("-3"), not between terms ("a-b").
        return t.indexOf('-', startIndex = 1) < 0
    }

    /** Sentences with right-to-left letters keep only short inline atoms; the rest becomes Formula lines after them. */
    fun mathOutOfRtl(doc: NoteDocument): NoteDocument = doc.copy(elements = moveOut(doc.elements))

    /** MarginRemark targets are indices into the top-level list: keeps them pointing at the same element. */
    private fun retarget(blocks: List<NoteBlock>, newIndex: IntArray): List<NoteBlock> = blocks.map { b ->
        val t = b.target ?: return@map b
        b.copy(target = newIndex.getOrNull(t)?.takeIf { it >= 0 })
    }

    private fun moveOut(blocks: List<NoteBlock>): List<NoteBlock> {
        val out = ArrayList<NoteBlock>(blocks.size)
        val newIndex = IntArray(blocks.size)
        for ((i, b) in blocks.withIndex()) {
            newIndex[i] = out.size
            val moved = ArrayList<String>()
            fun fix(s: String?): String? = s?.let { split(it, moved) }
            val fixed = when (b.element) {
                NoteElement.Columns -> b.copy(columns = b.columns.map(::moveOut))
                NoteElement.StepSolution -> b.copy(
                    steps = b.steps.map { st ->
                        val own = ArrayList<String>()
                        val text = st.text?.let { split(it, own) }
                        val why = st.why?.let { split(it, own) }
                        if (own.isEmpty()) st else st.copy(text = text?.takeIf { it.isNotBlank() }, why = why?.takeIf { it.isNotBlank() }, math = (listOfNotNull(st.math) + own).joinToString(" \\\\ "))
                    },
                )
                NoteElement.WorkedExample -> b.copy(
                    text = fix(b.text),
                    parts = b.parts.map { p ->
                        val own = ArrayList<String>()
                        val q = p.question?.let { split(it, own) }
                        if (own.isEmpty()) p else p.copy(question = q, steps = own + p.steps)
                    },
                )
                NoteElement.Bullets -> b.copy(items = b.items.map { split(it, moved) })
                in PROSE -> b.copy(text = fix(b.text), head = fix(b.head))
                else -> b
            }
            out += fixed
            for (tex in moved) out += NoteBlock(NoteElement.Formula, math = tex)
        }
        return retarget(out, newIndex)
    }

    /** [text] with its non-atomic inline maths removed (added to [moved]) when the sentence is right to left. */
    internal fun split(text: String, moved: MutableList<String>): String {
        if (!WritingScript.hasRtl(INLINE.replace(text, " "))) return text
        var changed = false
        val kept = INLINE.replace(text) { m ->
            val tex = (m.groups[1] ?: m.groups[2])!!.value.trim()
            if (isShortAtom(tex)) {
                m.value
            } else {
                moved += tex
                changed = true
                " "
            }
        }
        if (!changed) return text
        return kept.replace(Regex("\\s+([.,:;،؛])"), "$1").replace(Regex("\\s{2,}"), " ").trim()
    }

    private val PROSE = setOf(
        NoteElement.Paragraph, NoteElement.Definition, NoteElement.Theorem, NoteElement.Example, NoteElement.Note,
        NoteElement.Warning, NoteElement.Summary, NoteElement.StarNote, NoteElement.Title, NoteElement.Heading,
    )

    // ------------------------------------------------------------------ 2. allowed elements

    /** Each element the template does not allow, as the nearest one it does (or dropped when nothing fits). */
    fun allowedOnly(doc: NoteDocument, allowed: Set<NoteElement>): NoteDocument {
        fun fit(b: NoteBlock): NoteBlock? {
            if (b.element in allowed) return if (b.element == NoteElement.Columns) b.copy(columns = b.columns.map { c -> c.mapNotNull(::fit) }) else b
            if (b.element == NoteElement.Columns) return null
            val alt = FALLBACK[b.element].orEmpty().firstOrNull { it in allowed } ?: return null
            return b.copy(element = alt)
        }
        val out = ArrayList<NoteBlock>()
        val newIndex = IntArray(doc.elements.size)
        for ((i, b) in doc.elements.withIndex()) {
            newIndex[i] = out.size
            val pieces = if (b.element == NoteElement.Columns && NoteElement.Columns !in allowed) b.columns.flatten() else listOf(b)
            val fitted = pieces.mapNotNull(::fit)
            if (fitted.isEmpty()) newIndex[i] = -1
            out += fitted
        }
        return doc.copy(elements = retarget(out, newIndex))
    }

    private val FALLBACK: Map<NoteElement, List<NoteElement>> = mapOf(
        NoteElement.Title to listOf(NoteElement.Heading),
        NoteElement.Heading to listOf(NoteElement.Title, NoteElement.Paragraph),
        NoteElement.Paragraph to listOf(NoteElement.Note, NoteElement.Bullets, NoteElement.Summary),
        NoteElement.Theorem to listOf(NoteElement.Definition, NoteElement.Note, NoteElement.Paragraph),
        NoteElement.Definition to listOf(NoteElement.Theorem, NoteElement.Note, NoteElement.Paragraph),
        NoteElement.KeyResult to listOf(NoteElement.NumberedFormula, NoteElement.Formula),
        NoteElement.NumberedFormula to listOf(NoteElement.KeyResult, NoteElement.Formula),
        NoteElement.Formula to listOf(NoteElement.KeyResult),
        NoteElement.Example to listOf(NoteElement.WorkedExample, NoteElement.Note, NoteElement.Paragraph),
        NoteElement.WorkedExample to listOf(NoteElement.Example, NoteElement.StepSolution),
        NoteElement.Derivation to listOf(NoteElement.StepSolution),
        NoteElement.StepSolution to listOf(NoteElement.Derivation, NoteElement.Bullets),
        NoteElement.StarNote to listOf(NoteElement.Note, NoteElement.MarginRemark),
        NoteElement.Note to listOf(NoteElement.StarNote, NoteElement.MarginRemark, NoteElement.Paragraph),
        NoteElement.Warning to listOf(NoteElement.Note, NoteElement.StarNote, NoteElement.Paragraph),
        NoteElement.MarginRemark to listOf(NoteElement.Note, NoteElement.StarNote),
        NoteElement.Summary to listOf(NoteElement.Note, NoteElement.Paragraph),
        NoteElement.Comparison to listOf(NoteElement.Table, NoteElement.Bullets),
        NoteElement.Table to listOf(NoteElement.Comparison, NoteElement.Bullets),
        NoteElement.VariableLegend to listOf(NoteElement.Table, NoteElement.Bullets),
        NoteElement.Bullets to listOf(NoteElement.Paragraph, NoteElement.Note),
    )

    // ------------------------------------------------------------------ 3. numbers

    /** Numbers NumberedFormula and Derivation elements from [start] in reading order (an explicit number resets the count). */
    fun number(doc: NoteDocument, start: Int = 1): NoteDocument {
        var next = start
        fun walk(blocks: List<NoteBlock>): List<NoteBlock> = blocks.map { b ->
            when (b.element) {
                NoteElement.Columns -> b.copy(columns = b.columns.map(::walk))
                NoteElement.NumberedFormula, NoteElement.Derivation -> {
                    val n = b.number ?: next
                    next = n + 1
                    b.copy(number = n)
                }
                else -> b
            }
        }
        return doc.copy(elements = walk(doc.elements))
    }

    /** The highest formula number used in [doc] (0 when none), for numbering to continue on the next page. */
    fun lastNumber(doc: NoteDocument): Int = doc.flatten().mapNotNull { it.number }.maxOrNull() ?: 0

    /** "①" for 1 … "⑳" for 20, "(21)" after. */
    fun circled(n: Int): String = if (n in 1..20) (CIRCLED_ONE + n - 1).toChar().toString() else "($n)"

    private const val CIRCLED_ONE = 0x2460

    // ------------------------------------------------------------------ 4. glossary

    /**
     * Puts the page's terms that are new to the notebook into one Glossary element at the end (when
     * [allowElement]); Glossary elements the model wrote are folded into it. [NoteDocument.terms] keeps
     * every term of the page so the notebook glossary can accumulate them.
     */
    fun withGlossary(doc: NoteDocument, glossary: NotebookGlossary, allowElement: Boolean): NoteDocument {
        val all = doc.allTerms()
        val pageTerms = NotebookGlossary().merge(all).first.terms
        val rest = doc.elements.filter { it.element != NoteElement.Glossary }
        val fresh = glossary.newTerms(pageTerms)
        val tail = if (allowElement && fresh.isNotEmpty()) listOf(NoteBlock(NoteElement.Glossary, terms = fresh)) else emptyList()
        return NoteDocument(rest + tail, pageTerms)
    }

    private const val ATOM_CHARS = 12
}
