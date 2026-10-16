package app.bestnote.engine.handwriting

/**
 * What the synthetic stroke hand ([PageComposer]) can write: the Hershey Latin sets, the exported
 * Aref Ruqaa forms and the hand-drawn symbols. Text with anything else (Hebrew, Cyrillic, CJK, rare
 * symbols) is written with the handwriting fonts instead, so a missing letter never comes out as "?".
 */
object StrokeHand {
    fun canWrite(text: String): Boolean = text.all(::canWrite)

    fun canWrite(c: Char): Boolean = when {
        c.isWhitespace() -> true
        Symbols.has(c) -> true
        c in GlyphData.print.glyphs || c in GlyphData.cursive.glyphs -> true
        Arabic.isArabic(c.toString()) -> GlyphData.arabic.has(c.toString()) || c in '\u064B'..'\u065F' || c == '\u0640' || c == '\u0670'
        else -> false
    }

    /** Characters of [text] the stroke hand lacks (for diagnostics). */
    fun missing(text: String): Set<Char> = text.filterNot(::canWrite).toSet()

    /** True when every symbol of a formula can be drawn by the stroke hand. */
    fun canWriteLatex(latex: String): Boolean = MathParser.parse(latex).flatten().all(::canWriteNode)

    private fun canWriteNode(n: MathParser.Node): Boolean = when (n) {
        is MathParser.Text -> canWrite(n.s)
        is MathParser.Space -> true
        is MathParser.Group -> n.body.all(::canWriteNode)
        is MathParser.Frac -> n.num.all(::canWriteNode) && n.den.all(::canWriteNode)
        is MathParser.Scripts -> (n.base?.let(::canWriteNode) ?: true) && n.sup.orEmpty().all(::canWriteNode) && n.sub.orEmpty().all(::canWriteNode)
        is MathParser.Sqrt -> n.body.all(::canWriteNode)
    }

    /** Prose with inline maths (`\( … \)`, `$ … $`…): the words and every formula. */
    fun canWriteProse(text: String): Boolean {
        val table = ArrayList<String>()
        val encoded = InlineMath.encode(text, table)
        return canWrite(InlineMath.stripTokens(encoded)) && table.all(::canWriteLatex)
    }

    /** LaTeX commands in [latex] (or in prose's inline maths) that are written as their bare name. */
    fun unknownCommands(latex: String): Set<String> = MathParser.unknownCommands(latex)

    fun unknownCommandsInProse(text: String): Set<String> {
        val table = ArrayList<String>()
        InlineMath.encode(text, table)
        return table.flatMapTo(LinkedHashSet()) { MathParser.unknownCommands(it) }
    }
}
