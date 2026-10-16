package app.bestnote.feature.editor.text

import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.ParagraphFormat
import app.bestnote.core.model.ParagraphStyle
import app.bestnote.core.model.Paragraphs
import app.bestnote.core.model.RunStyle
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement
import app.bestnote.core.model.TextRuns

/** What a text box looks like mid-edit: its words, caret / selection ([selStart] may be after [selEnd]) and formatting. */
internal data class TextDraft(
    val box: TextElement,
    val selStart: Int,
    val selEnd: Int,
) {
    val text: String get() = box.text
    val min: Int get() = minOf(selStart, selEnd)
    val max: Int get() = maxOf(selStart, selEnd)
    val hasSelection: Boolean get() = selStart != selEnd
}

/** A character-style flag the format bar and Ctrl+B/I/U switch. */
internal enum class Flag { Bold, Italic, Underline, Strike }

/**
 * Typing into a text box on the page (TEXT-04), as pure state: every keystroke, IME update and format-bar tap goes
 * through here, which keeps style runs and paragraph formats aligned with the text, applies Markdown autoformat, and
 * keeps its own undo history for the edit (one Ctrl+Z or Backspace undoes an autoformat). The editor stores the
 * result as one document undo step when typing ends. [clock] gives milliseconds (typing within [GROUP_MS] is one step).
 */
internal class TextEditSession(initial: TextElement, caret: Int = initial.text.length, private val clock: () -> Long = System::currentTimeMillis) {
    var draft: TextDraft = TextDraft(
        initial.copy(runs = TextRuns.sanitized(initial.runs, initial.text.length), paragraphs = Paragraphs.normalized(initial.paragraphs, initial.text)),
        caret.coerceIn(0, initial.text.length),
        caret.coerceIn(0, initial.text.length),
    )
        private set

    /** Style the next typed characters get when the caret has no selection (Ctrl+B then type). */
    var pending: RunStyle? = null
        private set

    private val undoStack = ArrayDeque<TextDraft>()
    private val redoStack = ArrayDeque<TextDraft>()
    private var lastTypingAt = Long.MIN_VALUE / 2
    private var typingGroupOpen = false

    /** The literal text an autoformat replaced, while Backspace can still bring it back. */
    private var autoformatted: TextDraft? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** The box as it should be stored: trailing blank space trimmed, runs and paragraphs valid for the text. */
    fun result(): TextElement {
        val b = draft.box
        val text = b.text.trimEnd()
        return b.copy(text = text, runs = TextRuns.sanitized(b.runs, text.length), paragraphs = Paragraphs.normalized(b.paragraphs, text))
    }

    // ------------------------------------------------------------------ typing

    /**
     * The field reports [text] with selection [selStart]..[selEnd]. [composing] is true while the IME holds an
     * unfinished word (no autoformat then). @return true when the session changed the text beyond what was reported
     * (autoformat, list exit), so the field must take [draft] and drop its composition.
     */
    fun onInput(text: String, selStart: Int, selEnd: Int, composing: Boolean = false): Boolean {
        val old = draft
        if (text == old.text) {
            if (selStart != old.selStart || selEnd != old.selEnd) {
                draft = old.copy(selStart = selStart, selEnd = selEnd)
                if (draft.max != old.max || draft.min != old.min) {
                    pending = null
                    autoformatted = null
                    typingGroupOpen = false
                }
            }
            return false
        }
        val oldText = old.text
        var p = 0
        while (p < oldText.length && p < text.length && oldText[p] == text[p]) p++
        var q = 0
        while (q < oldText.length - p && q < text.length - p && oldText[oldText.length - 1 - q] == text[text.length - 1 - q]) q++
        val removed = oldText.length - p - q
        val inserted = text.substring(p, text.length - q)

        // Backspace right after an autoformat brings back the typed marker instead of deleting more.
        val literal = autoformatted
        if (literal != null && !composing && inserted.isEmpty() && removed == 1 && p + 1 == old.max) {
            undo()
            return true
        }
        autoformatted = null

        // Enter on an empty list item ends the list (or steps out one level) instead of adding another empty item.
        if (inserted == "\n" && removed == 0 && !composing) {
            val index = Paragraphs.indexAt(oldText, p)
            val range = Paragraphs.ranges(oldText)[index]
            val f = Paragraphs.formatOf(old.box.paragraphs, index)
            val listed = (f.list ?: old.box.list) != ListStyle.None
            if (listed && range.isEmpty()) {
                checkpoint(typing = false)
                val next = if (f.indent > 0) f.copy(indent = f.indent - 1) else f.copy(list = ListStyle.None)
                draft = old.copy(box = old.box.copy(paragraphs = withFormat(old.box, index, next)))
                return true
            }
        }

        val now = clock()
        // An IME replacing its composing word is still the same word: only finished words break the group.
        val breaksGroup = inserted.any { it == '\n' || it == ' ' } || (!composing && removed > 0 && inserted.isNotEmpty()) || now - lastTypingAt > GROUP_MS
        if (!typingGroupOpen || breaksGroup) checkpoint(typing = true)
        typingGroupOpen = true
        lastTypingAt = now

        var runs = TextRuns.edited(old.box.runs, oldText.length, p, removed, inserted.length)
        pending?.let { style -> if (inserted.isNotEmpty()) runs = TextRuns.restyled(runs, text.length, p, p + inserted.length) { style } }
        val paragraphs = Paragraphs.edited(old.box.paragraphs, oldText, p, removed, inserted)
        draft = TextDraft(old.box.copy(text = text, runs = runs, paragraphs = paragraphs), selStart, selEnd)

        if (composing || removed != 0 || inserted.length != 1 || draft.hasSelection) return false
        val match = Autoformat.detect(text, draft.max) ?: return false
        val literalDraft = draft
        checkpoint(typing = false)
        when (match) {
            is Autoformat.Paragraph -> {
                val index = Paragraphs.indexAt(text, match.start)
                val f = Paragraphs.formatOf(draft.box.paragraphs, index)
                val stripped = cut(draft, match.start, match.length)
                draft = stripped.copy(box = stripped.box.copy(paragraphs = withFormat(stripped.box, index, match.format(f))))
            }
            is Autoformat.InlineCode -> {
                // Remove the closing backtick first so the opening one's position still holds.
                val a = cut(cut(draft, match.close, 1), match.open, 1)
                val from = match.open
                val to = match.close - 1
                draft = a.copy(box = a.box.copy(runs = TextRuns.restyled(a.box.runs, a.text.length, from, to) { it.copy(font = TextElement.FONT_MONO) }))
            }
        }
        autoformatted = literalDraft
        typingGroupOpen = false
        // What is typed after `code` is plain again: it takes the style from before the code.
        if (match is Autoformat.InlineCode) {
            pending = if (match.open > 0) TextRuns.toStyles(draft.box.runs, draft.text.length)[match.open - 1] else RunStyle.PLAIN
        }
        return true
    }

    /** Backspace with nothing before the caret to delete (hardware key or IME at the start): undoes an autoformat. */
    fun backspaceAtStart(): Boolean {
        if (autoformatted == null) return false
        undo()
        return true
    }

    /** Typed characters that arrive before the field has focus (hardware keyboard), inserted at the caret. */
    fun type(chars: String) {
        val d = draft
        val text = d.text.substring(0, d.min) + chars + d.text.substring(d.max)
        val caret = d.min + chars.length
        onInput(text, caret, caret)
    }

    // ------------------------------------------------------------------ undo

    private fun checkpoint(typing: Boolean) {
        undoStack.addLast(draft)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        if (!typing) typingGroupOpen = false
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(draft)
        draft = prev
        reset()
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(draft)
        draft = next
        reset()
        return true
    }

    private fun reset() {
        autoformatted = null
        pending = null
        typingGroupOpen = false
    }

    // ------------------------------------------------------------------ character styles

    /** Whether [flag] is on at the selection (all of it) or, with a caret, for the next typed characters. */
    fun isOn(flag: Flag): Boolean {
        val d = draft
        val box = d.box
        if (boxHas(box, flag)) return true
        if (!d.hasSelection) {
            pending?.let { return has(it, flag) }
            return d.min > 0 && TextRuns.all(box.runs, d.text.length, d.min - 1, d.min) { has(it, flag) }
        }
        return TextRuns.all(box.runs, d.text.length, d.min, d.max) { has(it, flag) }
    }

    /** Switches [flag]: over the selection, or for what is typed next at the caret. */
    fun toggle(flag: Flag) {
        val d = draft
        val box = d.box
        val on = !isOn(flag)
        if (!d.hasSelection) {
            val base = pending ?: if (d.min > 0) TextRuns.toStyles(box.runs, d.text.length)[d.min - 1] else RunStyle.PLAIN
            if (!on && boxHas(box, flag)) {
                change { it.copy(box = withBoxFlag(it.box, flag, false)) }
            } else {
                pending = set(base, flag, on)
            }
            return
        }
        val whole = d.min == 0 && d.max >= d.text.length
        change { cur ->
            var b = cur.box
            if (!on && whole && boxHas(b, flag)) b = withBoxFlag(b, flag, false)
            cur.copy(box = b.copy(runs = TextRuns.restyled(b.runs, b.text.length, cur.min, cur.max) { set(it, flag, on) }))
        }
    }

    /** Colour of the selection (a run), or of the whole box when nothing is selected. */
    fun setColor(argb: Int) = change { cur ->
        if (cur.hasSelection) {
            cur.copy(box = cur.box.copy(runs = TextRuns.restyled(cur.box.runs, cur.text.length, cur.min, cur.max) { it.copy(colorArgb = argb.takeIf { c -> c != cur.box.colorArgb }) }))
        } else {
            cur.copy(box = cur.box.copy(colorArgb = argb))
        }
    }

    /** Font of the selection, or of the whole box (null = default sans). */
    fun setFont(id: String?) = change { cur ->
        if (cur.hasSelection) {
            cur.copy(box = cur.box.copy(runs = TextRuns.restyled(cur.box.runs, cur.text.length, cur.min, cur.max) { it.copy(font = id) }))
        } else {
            cur.copy(box = cur.box.copy(font = id))
        }
    }

    /** Text size: of the selection, or the box's base size (paragraph styles scale from it). */
    fun stepSize(deltaPt: Float) = change { cur ->
        if (cur.hasSelection) {
            val styles = TextRuns.toStyles(cur.box.runs, cur.text.length)
            val now = styles.getOrNull(cur.min)?.sizePt ?: cur.box.sizePt
            val next = (now + deltaPt).coerceIn(TextElement.MIN_SIZE_PT, TextElement.MAX_SIZE_PT)
            cur.copy(box = cur.box.copy(runs = TextRuns.restyled(cur.box.runs, cur.text.length, cur.min, cur.max) { it.copy(sizePt = next) }))
        } else {
            cur.copy(box = cur.box.copy(sizePt = (cur.box.sizePt + deltaPt).coerceIn(TextElement.MIN_SIZE_PT, TextElement.MAX_SIZE_PT)))
        }
    }

    fun setAlign(align: TextAlignment) = change { it.copy(box = it.box.copy(align = align)) }

    /** Takes what the "More" dialog returns (words and formatting), keeping the frame, paragraph formats and history. */
    fun replaceBox(next: TextElement) = change { cur ->
        val b = cur.box
        val text = next.text
        TextDraft(
            next.copy(id = b.id, cx = b.cx, cy = b.cy, width = b.width, height = b.height, rotation = b.rotation, paragraphs = Paragraphs.normalized(b.paragraphs, text)),
            cur.selStart.coerceAtMost(text.length),
            cur.selEnd.coerceAtMost(text.length),
        )
    }

    /** A growing new box changed width (not an undo step: it follows the words). Keeps the top edge. */
    fun reframe(left: Float, width: Float) {
        val b = draft.box
        draft = draft.copy(box = b.copy(cx = left + width / 2f, width = width))
    }

    // ------------------------------------------------------------------ paragraphs

    /** Indices of the paragraphs the caret or selection touches. */
    fun touched(): IntRange {
        val d = draft
        return Paragraphs.indexAt(d.text, d.min)..Paragraphs.indexAt(d.text, d.max)
    }

    /** The paragraph style at the caret (the first touched paragraph). */
    val style: ParagraphStyle get() = Paragraphs.formatOf(draft.box.paragraphs, touched().first).style

    /** The effective list at the caret. */
    val list: ListStyle get() = Paragraphs.formatOf(draft.box.paragraphs, touched().first).list ?: draft.box.list

    /** One tap: every touched paragraph becomes [style]; tapping the style they all have already goes back to Body. */
    fun setStyle(style: ParagraphStyle) {
        val all = touched().all { Paragraphs.formatOf(draft.box.paragraphs, it).style == style }
        val target = if (all && style != ParagraphStyle.Body) ParagraphStyle.Body else style
        editParagraphs { it.copy(style = target) }
    }

    /** Bullets or numbers on every touched paragraph, or off when they all have [kind] already. */
    fun toggleList(kind: ListStyle) {
        val box = draft.box
        val all = touched().all { (Paragraphs.formatOf(box.paragraphs, it).list ?: box.list) == kind }
        editParagraphs { it.copy(list = if (all) ListStyle.None else kind) }
    }

    /** Tab / Shift+Tab: indents list items one level. @return false when the caret is not in a list (Tab is then not ours). */
    fun indent(delta: Int): Boolean {
        val box = draft.box
        val listed = touched().all { (Paragraphs.formatOf(box.paragraphs, it).list ?: box.list) != ListStyle.None }
        if (!listed) return false
        editParagraphs { it.copy(indent = (it.indent + delta).coerceIn(0, ParagraphFormat.MAX_INDENT)) }
        return true
    }

    private fun editParagraphs(transform: (ParagraphFormat) -> ParagraphFormat) {
        val range = touched()
        change { cur ->
            var formats = cur.box.paragraphs
            for (i in range) formats = withFormat(cur.box.copy(paragraphs = formats), i, transform(Paragraphs.formatOf(formats, i)))
            cur.copy(box = cur.box.copy(paragraphs = formats))
        }
    }

    // ------------------------------------------------------------------ helpers

    private inline fun change(block: (TextDraft) -> TextDraft) {
        val next = block(draft)
        if (next == draft) return
        checkpoint(typing = false)
        draft = next
        autoformatted = null
    }

    /** [d] with [length] characters at [at] removed (runs and caret follow; no line breaks are removed). */
    private fun cut(d: TextDraft, at: Int, length: Int): TextDraft {
        val text = d.text.removeRange(at, at + length)
        fun shift(i: Int) = if (i <= at) i else maxOf(at, i - length)
        return TextDraft(d.box.copy(text = text, runs = TextRuns.edited(d.box.runs, d.text.length, at, length, 0)), shift(d.selStart), shift(d.selEnd))
    }

    private fun withFormat(box: TextElement, index: Int, f: ParagraphFormat): List<ParagraphFormat> {
        val n = Paragraphs.count(box.text)
        val list = (0 until n).map { Paragraphs.formatOf(box.paragraphs, it) }.toMutableList()
        if (index in list.indices) list[index] = f
        return Paragraphs.normalized(list, box.text)
    }

    private fun has(s: RunStyle, flag: Flag) = when (flag) {
        Flag.Bold -> s.bold
        Flag.Italic -> s.italic
        Flag.Underline -> s.underline
        Flag.Strike -> s.strike
    }

    private fun set(s: RunStyle, flag: Flag, on: Boolean) = when (flag) {
        Flag.Bold -> s.copy(bold = on)
        Flag.Italic -> s.copy(italic = on)
        Flag.Underline -> s.copy(underline = on)
        Flag.Strike -> s.copy(strike = on)
    }

    private fun boxHas(b: TextElement, flag: Flag) = when (flag) {
        Flag.Bold -> b.bold
        Flag.Italic -> b.italic
        Flag.Underline -> b.underline
        Flag.Strike -> b.strike
    }

    private fun withBoxFlag(b: TextElement, flag: Flag, on: Boolean) = when (flag) {
        Flag.Bold -> b.copy(bold = on)
        Flag.Italic -> b.copy(italic = on)
        Flag.Underline -> b.copy(underline = on)
        Flag.Strike -> b.copy(strike = on)
    }

    companion object {
        /** Keystrokes closer together than this are one undo step (until a space or line break). */
        const val GROUP_MS = 1_000L
        private const val MAX_UNDO = 200
    }
}
