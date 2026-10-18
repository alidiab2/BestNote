package app.bestnote.engine.layout.figlib

import app.bestnote.core.model.VectorDiagram

/** A school subject, for the catalogue and the evaluation set. */
enum class Subject { Physics, Maths, Chemistry, Biology, ComputerScience, Generic }

/**
 * One parametric drawing the library knows: its [kind] (the wire name), [aliases] a model might use,
 * a one-line [doc] for the prompt's catalogue (its rows), and [draw], which draws it with a [Pen]
 * from sanitised [Params] and says whether it could (false: rows it needs are missing).
 */
class FigElement(
    val kind: String,
    val subject: Subject,
    val doc: String,
    val aliases: List<String> = emptyList(),
    val draw: (Params, Pen) -> Boolean,
)

/** A spec drawn: the clean diagram (page coordinates, y down) and the labels as placed (for checks). */
class FigRender(val spec: FigSpec, val element: FigElement, val diagram: VectorDiagram, val labels: List<Pair<String, FloatArray>>)

/**
 * FIG-LIB: the library of parametric, editable board drawings. The AI says WHAT a drawing is (a kind
 * from [catalogue]) and its parameters; [render] draws it crisply in label units ([Pen.FONT]) with
 * every label beside its shape. A spec that names no known kind, or whose rows cannot draw it, renders
 * null, and the app falls back to fitting the photo's strokes (AI-12), then to a flagged picture.
 */
object FigureLibrary {
    val elements: List<FigElement> by lazy { Physics.all + Maths.all + Chemistry.all + Biology.all + Cs.all + Generic.all }

    private val byName: Map<String, FigElement> by lazy {
        val m = LinkedHashMap<String, FigElement>()
        for (e in elements) {
            m[key(e.kind)] = e
            for (a in e.aliases) m.putIfAbsent(key(a), e)
        }
        m
    }

    private fun key(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    /** The element for a kind name (aliases, case and punctuation forgiven); null when unknown. */
    fun find(kind: String?): FigElement? = kind?.let { byName[key(it)] }

    /** Below this confidence the photo's own strokes are tried first. */
    const val MIN_CONFIDENCE = 0.5f

    /** Draws [spec]; null when the kind is unknown, nothing could be drawn, or the drawing is degenerate. */
    fun render(spec: FigSpec): FigRender? {
        val e = find(spec.kind) ?: return null
        val pen = Pen()
        val drawn = runCatching { e.draw(spec.params, pen) }.getOrDefault(false)
        if (!drawn) return null
        val d = runCatching { pen.build() }.getOrNull() ?: return null
        if (d.viewBox.isEmpty || !d.viewBox.width.isFinite() || !d.viewBox.height.isFinite()) return null
        if (d.viewBox.width > MAX_SIDE || d.viewBox.height > MAX_SIDE) return null
        return FigRender(spec, e, d, pen.placed)
    }

    /** The prompt's catalogue: one line per kind, `kind: rows`. */
    fun catalogue(subjects: Set<Subject> = Subject.entries.toSet()): String = elements.filter { it.subject in subjects }.joinToString("\n") { "${it.kind}: ${it.doc}" }

    /** Largest drawing side in label units (a runaway parameter is a model slip). */
    private const val MAX_SIDE = 4000f
}
