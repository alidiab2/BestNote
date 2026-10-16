package app.bestnote.engine.layout

import app.bestnote.core.model.VectorDiagram

/**
 * What the AI writes for a template: an ordered list of built-in [NoteElement]s and their content,
 * nothing else (no coordinates, colours, sizes or ids). The app lays every element out
 * deterministically and styles it from the template's look, so layout quality never depends on the
 * model. This is also what Smart Board stores with each page it inserts (for follow-up photos).
 *
 * @property terms the page's technical terms, (term in the notes, term in the terms language).
 */
data class NoteDocument(val elements: List<NoteBlock>, val terms: List<GlossaryTerm> = emptyList()) {
    /** Every glossary term of the page: [terms] plus the pairs of its Glossary elements. */
    fun allTerms(): List<GlossaryTerm> = terms + flatten().filter { it.element == NoteElement.Glossary }.flatMap { it.terms }

    /** Elements in reading order, with the contents of [NoteElement.Columns] inlined after the column element. */
    fun flatten(): List<NoteBlock> = elements.flatMap { listOf(it) + it.columns.flatten() }

    val isEmpty: Boolean get() = elements.isEmpty()
}

/**
 * One element. Which fields matter depends on [element]:
 * - [text] (wire `t`): words with `\( … \)` inline maths — paragraph, definition body, note, problem statement;
 * - [head] (`h`): the term defined, a theorem's or example's name, a label;
 * - [math] (`m`): display LaTeX (Formula, KeyResult, NumberedFormula);
 * - [items]: bullet lines; [steps]: StepSolution / Derivation lines; [rows]: Table, Comparison (row 0 = headers),
 *   VariableLegend (symbol, meaning, unit), WorkedExample "given" (symbol, value, unit);
 * - [terms]: Glossary pairs; [target]: MarginRemark → index of the element it comments on;
 * - [number] (`n`): a NumberedFormula's or Derivation result's number (null = next in order);
 * - [columns] (`cols`): Columns → two lists of elements; [parts]: WorkedExample sub-questions;
 * - [diagram]: Diagram, already drawn as vector shapes (also from the data kinds in [DiagramKinds]).
 */
data class NoteBlock(
    val element: NoteElement,
    val text: String? = null,
    val head: String? = null,
    val math: String? = null,
    val items: List<String> = emptyList(),
    val steps: List<NoteStep> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val terms: List<GlossaryTerm> = emptyList(),
    val target: Int? = null,
    val number: Int? = null,
    val columns: List<List<NoteBlock>> = emptyList(),
    val parts: List<NotePart> = emptyList(),
    val diagram: VectorDiagram? = null,
) {
    /** All the words of the element (for direction and script detection). */
    fun words(): String = buildString {
        listOfNotNull(head, text).forEach { append(it).append(' ') }
        items.forEach { append(it).append(' ') }
        steps.forEach { s -> listOfNotNull(s.text, s.why).forEach { append(it).append(' ') } }
        rows.forEach { r -> r.forEach { append(it).append(' ') } }
        terms.forEach { append(it.term).append(' ') }
        parts.forEach { p -> p.question?.let { append(it).append(' ') } }
    }
}

/** A step: words (with inline maths) and/or a display formula, and the short reason it holds. */
data class NoteStep(val text: String? = null, val math: String? = null, val why: String? = null)

/** A WorkedExample sub-question: its words, the solution lines (LaTeX), and the result with its unit (LaTeX). */
data class NotePart(val question: String? = null, val steps: List<String> = emptyList(), val result: String? = null)

/** A glossary pair: the term as the notes write it and its [translation] in the terms language. */
data class GlossaryTerm(val term: String, val translation: String)
