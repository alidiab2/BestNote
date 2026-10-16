package app.bestnote.engine.handwriting

import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteStep

/**
 * Realistic Smart Board answers rebuilt from the owner's on-device results (DeepSeek, Classic
 * template, Arabic notes): the elements and LaTeX exactly as the model wrote them, including what
 * used to render badly (cases, |x|, raw LaTeX in table cells, a mixed Arabic + Hebrew title, a
 * glossary repeating the notes' own words).
 */
internal object Fixtures {
    private fun f(m: String) = NoteBlock(NoteElement.Formula, math = m)
    private fun p(t: String) = NoteBlock(NoteElement.Paragraph, text = t)
    private fun h(t: String) = NoteBlock(NoteElement.Heading, text = t)

    /** Partial fractions, Arabic notes with the Hebrew terms (owner screens 4ea68eed, 6e3186b0, 5a9356aa). */
    fun partialFractionsArabic() = NoteDocument(
        listOf(
            NoteBlock(NoteElement.Title, text = "التكامل بالكسور الجزئية (אינטגרל בשברים חלקיים)"),
            h("طريقة الكسور الجزئية"),
            p("نكتب الكسر بمقام قابل للتحليل ثم نفككه إلى كسور أبسط."),
            f("\\int \\frac{1}{x^2+5x+6}\\,dx"),
            h("الخطوة ١: تحليل المقام"),
            f("x^2+5x+6=(x+2)(x+3)"),
            f("\\int \\frac{1}{x^2+5x+6}\\,dx=\\int \\frac{1}{(x+2)(x+3)}\\,dx"),
            h("الخطوة ٢: التفكيك إلى كسور جزئية"),
            f("\\frac{1}{(x+2)(x+3)}=\\frac{a}{x+2}+\\frac{b}{x+3}"),
            f("\\frac{1}{(x+2)(x+3)}=\\frac{a(x+3)+b(x+2)}{(x+2)(x+3)}"),
            NoteBlock(
                NoteElement.StepSolution,
                head = "نساوي المعاملات",
                steps = listOf(
                    NoteStep(math = "1=a(x+3)+b(x+2)"),
                    NoteStep(math = "1=ax+3a+bx+2b"),
                    NoteStep(math = "0x+1=x(a+b)+3a+2b", why = "نرتب حسب قوى x"),
                ),
            ),
            h("الخطوة ٣: حل المعادلتين"),
            f("\\begin{cases} a+b=0 \\\\ 3a+2b=1 \\end{cases}"),
            f("a=1"),
            f("b=-1"),
            h("الخطوة ٤: التعويض والتكامل"),
            f("\\int\\left(\\frac{1}{x+2}+\\frac{-1}{x+3}\\right)dx=\\int\\frac{1}{x+2}\\,dx-\\int\\frac{1}{x+3}\\,dx"),
            NoteBlock(NoteElement.KeyResult, math = "\\ln|x+2|-\\ln\\left|x+3\\right|+c"),
            NoteBlock(NoteElement.Note, text = "الجدول المساعد لحل المعادلتين: الصف الأول \\(a\\)، والصف الثاني \\(b\\) حسب قوى \\(x\\)."),
            h("جدول الحالات"),
            NoteBlock(
                NoteElement.Table,
                rows = listOf(
                    listOf("المقام", "الشكل"),
                    listOf("عامل خطي", "\\frac{a}{x^0}"),
                    listOf("عامل خطي مكرر", "\\frac{a}{x^1}"),
                    listOf("عامل خطي مكرر مرتين", "\\frac{a}{x^2}"),
                    listOf("عامل تربيعي", "\\frac{bx+c}{x^2}"),
                ),
            ),
        ),
        terms = listOf(
            GlossaryTerm("التكامل", "التكامل"),
            GlossaryTerm("الكسور الجزئية", "الكسور الجزئية"),
            GlossaryTerm("تحليل المقام", "تحليل المقام"),
            GlossaryTerm("المقام", "المقام"),
            GlossaryTerm("البسط", "البسط"),
            GlossaryTerm("اللوغاريتم", "اللوغاريتم"),
        ),
    )

    /** The same board with Hebrew terms set: the glossary has real pairs. */
    fun partialFractionsWithHebrewTerms() = partialFractionsArabic().let { d ->
        d.copy(
            terms = listOf(
                GlossaryTerm("التكامل", "אינטגרל"),
                GlossaryTerm("الكسور الجزئية", "שברים חלקיים"),
                GlossaryTerm("المقام", "מכנה"),
                GlossaryTerm("البسط", "מונה"),
                GlossaryTerm("اللوغاريتم", "לוגריתם"),
            ),
        )
    }

    /** L'Hôpital on x²·ln x (the owner's test board), Arabic notes. */
    fun lHopitalArabic() = NoteDocument(
        listOf(
            NoteBlock(NoteElement.Title, text = "قاعدة لوبيتال (כלל לופיטל)"),
            h("الحالة ∞·0"),
            p("نحوّل الضرب إلى قسمة لنحصل على حالة \\(\\frac{\\infty}{\\infty}\\) أو \\(\\frac{0}{0}\\) ثم نشتق البسط والمقام."),
            f("\\lim_{x\\to 0^+} x^2\\ln x = (0\\cdot(-\\infty))"),
            NoteBlock(
                NoteElement.StepSolution,
                steps = listOf(
                    NoteStep(math = "\\lim_{x\\to 0^+} x^2\\ln x=\\lim_{x\\to 0^+}\\frac{\\ln x}{\\frac{1}{x^2}}", why = "نكتب الضرب كقسمة"),
                    NoteStep(math = "=\\frac{-\\infty}{\\infty}", why = "حالة لوبيتال"),
                    NoteStep(math = "\\lim_{x\\to 0^+}\\frac{\\frac{1}{x}}{-2x^{-3}}=\\lim_{x\\to 0^+}\\frac{x^3}{-2x}", why = "نشتق البسط والمقام"),
                    NoteStep(math = "=\\lim_{x\\to 0^+}\\frac{x^2}{-2}=0"),
                ),
            ),
            NoteBlock(NoteElement.KeyResult, math = "\\lim_{x\\to 0^+} x^2\\ln x=0"),
            h("تذكير: كسر مركب"),
            f("\\frac{4}{\\frac{1}{3}}=3\\cdot 4=12"),
            f("\\frac{\\infty}{\\frac{1}{0}}=0\\cdot\\infty"),
            NoteBlock(NoteElement.Note, text = "لا نطبق لوبيتال إلا على \\(\\frac{0}{0}\\) أو \\(\\frac{\\infty}{\\infty}\\)."),
            NoteBlock(NoteElement.Warning, text = "\\(0\\cdot\\infty\\) ليست صفرًا: نحولها أولًا إلى كسر."),
        ),
        terms = listOf(GlossaryTerm("قاعدة لوبيتال", "כלל לופיטל"), GlossaryTerm("نهاية", "גבול"), GlossaryTerm("مشتقة", "נגזרת")),
    )
}
