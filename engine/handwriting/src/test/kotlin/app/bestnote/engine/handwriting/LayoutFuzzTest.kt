package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NotePart
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.NoteStep
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.DiagramKinds
import app.bestnote.core.model.EquationStyle
import app.bestnote.engine.layout.qa.LayoutIssue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * LAYOUT-QA: lays out many notes (the samples, the owner's real boards, random mixes of long
 * Hebrew/Arabic/English text, bidi with numbers and units, tall formulas, boxes, headings, lists,
 * tables, figures) × every built-in template × fonts × font scales × page sizes and orientations,
 * and fails on any overlapping, clipped or unreadable writing with a minimal reproducing case.
 *
 * Fast seed set by default; `-Playoutfuzz.iterations=N` runs N random cases (deep mode),
 * `-Playoutfuzz.png=true` writes the failing pages to build/layout-qa, `-Playoutfuzz.only=<text>` keeps
 * the cases whose description contains it (one case to debug). Every issue: build/layout-qa/issues.txt.
 */
class LayoutFuzzTest {
    data class Case(val doc: NoteDocument, val template: NoteTemplate, val fonts: Fonts, val scale: Float, val size: PageSize, val name: String, val typesetMath: Boolean = false) {
        override fun toString() = "$name · ${template.id} · ${fonts.name} · ×$scale · ${size.widthPt.toInt()}×${size.heightPt.toInt()}" + if (typesetMath) " · typeset maths" else ""
    }

    data class Fonts(val name: String, val latin: HandFont, val hebrew: HandFont, val arabic: HandFont)

    private val iterations = System.getProperty("layoutfuzz.iterations")?.toIntOrNull() ?: 0
    private val writePng = System.getProperty("layoutfuzz.png") == "true"
    private val only = System.getProperty("layoutfuzz.only")

    @Test
    fun noOverlapsAcrossTemplatesFontsAndSizes() {
        val cases = (if (iterations > 0) randomCases(iterations, Random(System.getProperty("layoutfuzz.seed")?.toLongOrNull() ?: 7L)) else seedCases())
            .filter { only == null || only in it.toString() }
        val failures = ArrayList<Pair<Case, List<LayoutIssue>>>()
        val counts = HashMap<LayoutIssue.Type, Int>()
        val started = System.nanoTime()
        var pages = 0
        for (c in cases) {
            val typeset = typeset(c)
            pages += typeset.pages.pages.size
            val issues = LayoutQa.check(typeset)
            if (issues.isEmpty()) continue
            issues.forEach { counts.merge(it.type, 1, Int::plus) }
            failures += c to issues
        }
        val secs = (System.nanoTime() - started) / 1e9
        println("LAYOUT-QA: ${cases.size} cases ($pages pages) in %.1f s, ${failures.size} failing, issues $counts".format(secs))
        // Every issue, one per line, for triage: build/layout-qa/issues.txt.
        java.io.File("build/layout-qa").apply { mkdirs() }.resolve("issues.txt").writeText(failures.joinToString("") { (c, issues) -> issues.joinToString("") { "$c | $it\n" } })
        if (failures.isEmpty()) return
        val report = StringBuilder()
        // A few examples of every kind of issue first, so a rare kind is not hidden behind a common one.
        for (t in LayoutIssue.Type.entries) {
            val ex = failures.flatMap { (c, issues) -> issues.filter { it.type == t }.map { c to it } }.distinctBy { it.second.a.owner + it.first.name }.take(4)
            ex.forEach { (c, i) -> report.append("\n[").append(t).append("] ").append(c).append("\n   ").append(i) }
        }
        report.append('\n')
        for ((c, issues) in failures.take(MAX_REPORTED)) {
            val min = shrink(c, issues.first().type)
            val minIssues = issuesOf(min)
            report.append("\n== ").append(c).append(" (${issues.size} issues)\n")
            issues.take(4).forEach { report.append("   ").append(it).append('\n') }
            report.append("   minimal: ").append(min.doc.elements.map { it.element }).append('\n')
            min.doc.elements.forEach { report.append("     ").append(it.toString().take(240)).append('\n') }
            minIssues.take(2).forEach { report.append("     → ").append(it).append('\n') }
            if (writePng) {
                val pages = typeset(c)
                issues.map { it.page }.distinct().take(2).forEach { p -> LayoutQa.png(pages, p, issues, "${c.name}-${c.template.id}-${c.fonts.name}-${c.scale}-p$p".replace(Regex("[^A-Za-z0-9.-]"), "_")) }
            }
        }
        println(report)
        fail("LAYOUT-QA: ${failures.size}/${cases.size} cases with overlapping/clipped writing, $counts$report")
    }

    // ------------------------------------------------------------------ running

    private fun typeset(c: Case): NotePages {
        val look = c.template.look.copy(
            latinFont = c.fonts.latin,
            hebrewFont = c.fonts.hebrew,
            arabicFont = c.fonts.arabic,
            bodyScale = c.template.look.bodyScale * c.scale,
            equationStyle = if (c.typesetMath) EquationStyle.Typeset else c.template.look.equationStyle,
        )
        val template = c.template.copy(look = look)
        val paper = RuledPaper.of(look.paper ?: PaperTemplate.Ruled, c.size)
        return NoteLayout.typeset(NoteNormalizer.normalize(c.doc, template), template, paper, AwtMeasure)
    }

    private fun issuesOf(c: Case): List<LayoutIssue> = LayoutQa.check(typeset(c))

    /** Drops elements (then bullet items and steps) while the case still shows a [type] issue. */
    private fun shrink(c: Case, type: LayoutIssue.Type): Case {
        var cur = c
        var changed = true
        while (changed) {
            changed = false
            for (i in cur.doc.elements.indices.reversed()) {
                if (cur.doc.elements.size <= 1) break
                val next = cur.copy(doc = cur.doc.copy(elements = cur.doc.elements.filterIndexed { k, _ -> k != i }, terms = emptyList()))
                if (issuesOf(next).any { it.type == type }) {
                    cur = next
                    changed = true
                    break
                }
            }
        }
        return cur
    }

    // ------------------------------------------------------------------ cases

    private val templates = NoteTemplates.builtIns()

    private val fontSets = listOf(
        Fonts("hands", HandFont.Caveat, HandFont.GveretLevin, HandFont.ArefRuqaa),
        Fonts("sans", HandFont("instrument-sans"), HandFont("heebo"), HandFont("ibm-plex-sans-arabic")),
        Fonts("serif", HandFont("lora"), HandFont("frank-ruhl-libre"), HandFont("noto-naskh-arabic")),
        Fonts("mono", HandFont("jetbrains-mono"), HandFont.GveretLevin, HandFont.ArefRuqaa),
        Fonts("fraunces", HandFont("fraunces"), HandFont("heebo"), HandFont("noto-naskh-arabic")),
    )

    private val sizes = listOf(
        PageSize.A4,
        PageSize(PageSize.A4.heightPt, PageSize.A4.widthPt),
        PageSize(420f, 595f), // A5
        PageSize.Letter,
        PageSize(PageSize.Letter.heightPt, PageSize.Letter.widthPt),
    )

    private val scales = listOf(0.8f, 1f, 1.25f, 1.6f)

    private fun docs(): List<Pair<String, NoteDocument>> = listOf(
        "en-calculus" to NoteSamples.englishCalculus(),
        "ar-calculus" to NoteSamples.arabicCalculus(),
        "he-physics" to NoteSamples.hebrewPhysics(),
        "board-partial-fractions" to Fixtures.partialFractionsArabic(),
        "board-lhopital" to Fixtures.lHopitalArabic(),
        "board-physics-work" to physicsWorkBoard(),
    )

    /** Every document × template once at scale 1 on A4, plus every font set and size, scale and orientation on a rotating subset. */
    private fun seedCases(): List<Case> {
        val out = ArrayList<Case>()
        val docs = docs() + (0 until 4).map { "random$it" to randomDoc(Random(100L + it)) }
        for ((name, d) in docs) for (t in templates) out += Case(d, t, fontSets[0], 1f, PageSize.A4, name)
        var k = 0
        for ((name, d) in docs) for (f in fontSets.drop(1)) for (s in listOf(0.8f, 1.6f)) {
            out += Case(d, templates[k % templates.size], f, s, sizes[k % sizes.size], name)
            k++
        }
        for (sz in sizes.drop(1)) for (t in templates) out += Case(docs[k++ % docs.size].second, t, fontSets[k % fontSets.size], scales[k % scales.size], sz, docs[(k - 1) % docs.size].first)
        return out
    }

    private fun randomCases(n: Int, rnd: Random): List<Case> = List(n) { i ->
        val pick = rnd.nextInt(4)
        val (name, d) = if (pick == 0) docs().random(rnd) else "random-$i" to randomDoc(rnd)
        Case(d, templates.random(rnd), fontSets.random(rnd), 0.8f + rnd.nextInt(9) * 0.1f, sizes.random(rnd), name, typesetMath = rnd.nextInt(4) == 0)
    }

    // ------------------------------------------------------------------ content

    private val english = listOf(
        "The work done by non-conservative forces equals the change in the total mechanical energy of the system.",
        "Integrate by parts twice and solve for the original integral",
        "Short note",
        "When the denominator factors into distinct linear terms we split the fraction into simpler pieces and integrate each one separately.",
    )
    private val hebrew = listOf(
        "עבודת הכוחות הלא משמרים שווה לשינוי באנרגיה הכוללת של המערכת",
        "גוף נזרק מגובה 5 (m) מעל הקרקע עם מהירות התחלתית כלפי מעלה של 10 (m/s). מהו הגובה המקסימלי?",
        "חוק שימור האנרגיה",
        "כאשר עבודת הכוחות הלא משמרים שווה אפס, מתקיים חוק שימור האנרגיה: האנרגיה הכוללת במצב ההתחלתי שווה לאנרגיה הכוללת במצב הסופי.",
    )
    private val arabic = listOf(
        "نكتب الكسر بمقام قابل للتحليل ثم نفككه إلى كسور أبسط ونكامل كل كسر على حدة",
        "قاعدة لوبيتال",
        "السرعة هي 10 m/s بعد t = 5 s من بداية الحركة، والتسارع 2 m/s^2",
    )
    private val mixed = listOf(
        "המהירות v = 10 m/s בזמן t = 5 s והתאוצה a = 2 m/s²",
        "לפי \\(W_{\\Sigma F} = \\Delta E_k\\) נקבל \\(\\frac{1}{2}mv^2\\) בסוף",
        "We write \\(\\lim_{x\\to\\infty} f(x) = L\\) when f gets close to L",
        "حسب \\(\\int_0^1 x^2\\,dx = \\frac{1}{3}\\) نحصل على النتيجة",
    )
    private val formulas = listOf(
        "W_{\\Sigma F} = \\Delta E_k",
        "\\frac{1}{(x+2)(x+3)}=\\frac{a}{x+2}+\\frac{b}{x+3}",
        "\\int_0^{\\infty} e^{-x^2}\\,dx = \\frac{\\sqrt{\\pi}}{2}",
        "\\sum_{i=1}^{n} i^2 = \\frac{n(n+1)(2n+1)}{6}",
        "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}\\begin{pmatrix} x \\\\ y \\end{pmatrix} = \\begin{pmatrix} ax+by \\\\ cx+dy \\end{pmatrix}",
        "\\frac{\\frac{1}{x}}{-2x^{-3}}",
        "E_{k_A}+U_{G_A}+U_{sp_A}=E_{k_B}+U_{G_B}+U_{sp_B}",
        "W_{sp}+W_{mg}+\\cancel{W_N}+W_F+W_{f_k}+\\cancel{W_T}=\\Delta E_k",
        "\\underbrace{W_{nc}}_{0} = \\Delta E_T",
        "\\begin{cases} a+b=0 \\\\ 3a+2b=1 \\end{cases}",
        "x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}",
        "\\lim_{x\\to 0^+}\\frac{\\ln x}{\\frac{1}{x^2}}=\\lim_{x\\to 0^+}\\frac{x^3}{-2x}=\\lim_{x\\to 0^+}\\frac{x^2}{-2}=0",
        "a=1",
    )

    private fun text(rnd: Random): String = when (rnd.nextInt(5)) {
        0 -> english.random(rnd)
        1 -> arabic.random(rnd)
        2 -> mixed.random(rnd)
        else -> hebrew.random(rnd)
    }

    private fun randomDoc(rnd: Random): NoteDocument {
        val els = ArrayList<NoteBlock>()
        els += NoteBlock(NoteElement.Title, text = text(rnd).take(40))
        repeat(6 + rnd.nextInt(10)) { els += randomBlock(rnd) }
        return NoteDocument(els)
    }

    private fun randomBlock(rnd: Random): NoteBlock = when (rnd.nextInt(14)) {
        0, 1 -> NoteBlock(NoteElement.Heading, text = text(rnd).take(rnd.nextInt(12, 40)))
        2 -> NoteBlock(NoteElement.Paragraph, text = text(rnd))
        3 -> NoteBlock(NoteElement.Bullets, items = List(rnd.nextInt(2, 5)) { text(rnd) })
        4, 5 -> NoteBlock(NoteElement.Formula, math = formulas.random(rnd))
        6 -> NoteBlock(NoteElement.KeyResult, math = formulas.random(rnd))
        7 -> NoteBlock(NoteElement.NumberedFormula, math = formulas.random(rnd))
        8 -> NoteBlock(NoteElement.Definition, head = text(rnd).take(16), text = text(rnd))
        9 -> NoteBlock(NoteElement.Note, text = mixed.random(rnd))
        10 -> NoteBlock(NoteElement.StepSolution, steps = List(rnd.nextInt(2, 4)) { NoteStep(math = formulas.random(rnd), why = if (rnd.nextBoolean()) text(rnd).take(20) else null) })
        11 -> NoteBlock(NoteElement.Table, rows = listOf(listOf("גודל", "יחידה", "ערך")) + List(rnd.nextInt(1, 4)) { listOf(text(rnd).take(14), "m/s", formulas.random(rnd).take(18)) })
        12 -> NoteBlock(NoteElement.Diagram, head = "תרשים כוחות", diagram = DiagramKinds.freeBody(listOf(DiagramKinds.Force("N", 90f), DiagramKinds.Force("mg", 270f))))
        else -> NoteBlock(
            NoteElement.Columns,
            columns = listOf(
                listOf(NoteBlock(NoteElement.Heading, text = hebrew.random(rnd).take(24)), NoteBlock(NoteElement.Formula, math = formulas.random(rnd))),
                listOf(NoteBlock(NoteElement.Paragraph, text = text(rnd)), NoteBlock(NoteElement.KeyResult, math = formulas.random(rnd))),
            ),
        )
    }

    /** The owner's physics board (test-board-4, v0.75): heading + underline, formula right under it, two columns, boxed results. */
    private fun physicsWorkBoard() = NoteDocument(
        listOf(
            NoteBlock(NoteElement.Title, text = "עבודה ואנרגיה"),
            NoteBlock(NoteElement.Formula, math = "W_T = T\\cdot\\cos 180^\\circ\\cdot\\Delta S = -T\\cdot\\Delta S"),
            NoteBlock(NoteElement.Heading, text = "עבודת הכוחות הלא משמרים"),
            NoteBlock(NoteElement.Formula, math = "W_{\\Sigma F} = \\Delta E_k"),
            NoteBlock(NoteElement.Formula, math = "W_{sp}+W_{mg}+\\cancel{W_N}+W_F+W_{f_k}+\\cancel{W_T}=\\Delta E_k"),
            NoteBlock(NoteElement.Formula, math = "W_F+W_{f_k}=\\Delta E_k+\\Delta U_{sp}+\\Delta U_G"),
            NoteBlock(NoteElement.Formula, math = "W_{\\text{לא משמרים}}=(E_{k_2}+U_{sp_2}+U_{G_2})-(E_{k_1}+U_{sp_1}+U_{G_1})"),
            NoteBlock(NoteElement.NumberedFormula, math = "W_{\\text{לא משמרים}}=E_{T_2}-E_{T_1}"),
            NoteBlock(NoteElement.KeyResult, math = "W_{\\text{לא משמרים}}=\\Delta E_T"),
            NoteBlock(NoteElement.Bullets, items = listOf("עבודת הכוחות הלא משמרים שווה לשינוי באנרגיה הכוללת.")),
            NoteBlock(NoteElement.Heading, text = "חוק שימור האנרגיה"),
            NoteBlock(NoteElement.Bullets, items = listOf("כאשר עבודת הכוחות הלא משמרים שווה אפס, מתקיים חוק שימור האנרגיה.", "האנרגיה הכוללת במצב ההתחלתי שווה לאנרגיה הכוללת במצב הסופי.")),
            NoteBlock(NoteElement.Formula, math = "0 = E_{T_B}-E_{T_A}"),
            NoteBlock(NoteElement.NumberedFormula, math = "E_{T_A} = E_{T_B}"),
            NoteBlock(NoteElement.Formula, math = "E_{k_A}+U_{G_A}+U_{sp_A}=E_{k_B}+U_{G_B}+U_{sp_B}"),
            NoteBlock(NoteElement.Heading, text = "דוגמה (1)"),
            NoteBlock(
                NoteElement.WorkedExample,
                text = "גוף נזרק מגובה 5 (m) מעל הקרקע עם מהירות התחלתית כלפי מעלה של 10 (m/s). מהו הגובה המקסימלי שאליו מגיע הגוף?",
                rows = listOf(listOf("h_A", "5", "m"), listOf("v_A", "10", "m/s")),
                parts = listOf(NotePart("הגובה המקסימלי?", listOf("E_{T_A}=E_{T_B}", "\\frac{1}{2}mv_A^2+mgh_A=mgh_B"), "h_B = 10\\,(m)")),
            ),
        ),
    )

    companion object {
        private const val MAX_REPORTED = 8
    }
}
