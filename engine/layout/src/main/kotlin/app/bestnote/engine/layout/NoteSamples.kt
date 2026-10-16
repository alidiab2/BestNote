package app.bestnote.engine.layout

/**
 * Sample notes for template previews (the template picker, the template editor) and the offline
 * demo provider. [forTemplate] picks the one that shows a template's elements best.
 */
object NoteSamples {
    /** The sample a preview of [template] should show, limited to the elements it allows. */
    fun forTemplate(template: NoteTemplate): NoteDocument {
        val base = when (template.ai.notesLanguage?.lowercase()) {
            "en" -> englishCalculus()
            "he" -> hebrewPhysics()
            "ar" -> arabicCalculus()
            else -> if (NoteElement.Columns in template.elements) hebrewPhysics() else arabicCalculus()
        }
        return NoteNormalizer.allowedOnly(base, template.elements)
    }

    /** The same lesson as [arabicCalculus] in English, with Hebrew terms (the TPL-02 preview for English notes). */
    fun englishCalculus() = NoteDocument(
        elements = listOf(
            NoteBlock(NoteElement.Title, text = "The derivative"),
            NoteBlock(
                NoteElement.Definition,
                head = "Derivative",
                text = "The slope of the tangent to \\(f\\) at \\(x_0\\): the limit \\(\\lim_{h\\to 0}\\frac{f(x_0+h)-f(x_0)}{h}\\) when it exists.",
            ),
            NoteBlock(NoteElement.KeyResult, head = "Power rule", math = "(x^n)' = n x^{n-1}"),
            NoteBlock(NoteElement.MarginRemark, text = "holds for every real n", target = 2),
            NoteBlock(
                NoteElement.StepSolution,
                head = "Example: differentiate \\(e^x\\)",
                steps = listOf(
                    NoteStep(math = "f(x) = e^{x}", why = "the exponential function"),
                    NoteStep(text = "substitute into \\(\\frac{e^{x+h}-e^{x}}{h}\\)", why = "definition of the derivative"),
                    NoteStep(math = "f'(x) = e^{x}", why = "because \\(\\lim_{h\\to0}\\frac{e^h-1}{h}=1\\)"),
                ),
            ),
            NoteBlock(NoteElement.Warning, text = "There is no derivative at a sharp corner, e.g. \\(|x|\\) at zero."),
            NoteBlock(
                NoteElement.Comparison,
                rows = listOf(listOf("Continuous", "Differentiable"), listOf("no jumps", "no corners"), listOf("not enough", "implies continuity")),
            ),
            NoteBlock(NoteElement.Diagram, head = "Tangent", diagram = DiagramKinds.axes("x", "f(x)", listOf(0f to 0f, 1f to 0.3f, 2f to 1.2f, 3f to 2.8f))),
            NoteBlock(NoteElement.Summary, items = listOf("derivative = slope of the tangent", "\\((e^x)' = e^x\\)", "differentiable implies continuous")),
        ),
        terms = listOf(
            GlossaryTerm("derivative", "נגזרת"),
            GlossaryTerm("limit", "גבול"),
            GlossaryTerm("tangent", "משיק"),
            GlossaryTerm("continuous", "רציפה"),
        ),
    )

    /**
     * Arabic notes with Hebrew terms and maths, the way an Arabic-speaking student at a Hebrew
     * university writes them: a fraction and a limit sit inside Arabic sentences (moved to their own
     * lines by [NoteNormalizer.mathOutOfRtl]), short atoms stay inline.
     */
    fun arabicCalculus() = NoteDocument(
        elements = listOf(
            NoteBlock(NoteElement.Title, text = "المشتقة (נגזרת)"),
            NoteBlock(
                NoteElement.Definition,
                head = "المشتقة",
                text = "ميل المماس للدالة \\(f\\) عند النقطة \\(x_0\\)، وهي النهاية \\(\\lim_{h\\to 0}\\frac{f(x_0+h)-f(x_0)}{h}\\) إن وُجدت.",
            ),
            NoteBlock(NoteElement.KeyResult, head = "قاعدة", math = "(x^n)' = n x^{n-1}"),
            NoteBlock(NoteElement.MarginRemark, text = "تنطبق لكل n حقيقي", target = 2),
            NoteBlock(
                NoteElement.StepSolution,
                head = "مثال: اشتقاق \\(e^x\\)",
                steps = listOf(
                    NoteStep(math = "f(x) = e^{x}", why = "الدالة الأسية"),
                    NoteStep(text = "نعوّض في التعريف \\(\\frac{e^{x+h}-e^{x}}{h}\\)", why = "تعريف المشتقة"),
                    NoteStep(math = "f'(x) = e^{x}", why = "لأن \\(\\lim_{h\\to0}\\frac{e^h-1}{h}=1\\)"),
                ),
            ),
            NoteBlock(NoteElement.Warning, text = "المشتقة غير موجودة عند زاوية حادة، مثل \\(|x|\\) عند الصفر."),
            NoteBlock(
                NoteElement.Comparison,
                rows = listOf(listOf("متصلة (רציפה)", "قابلة للاشتقاق (גזירה)"), listOf("لا قفزات", "لا زوايا"), listOf("لا تكفي للاشتقاق", "تستلزم الاتصال")),
            ),
            NoteBlock(NoteElement.Diagram, head = "المماس", diagram = DiagramKinds.axes("x", "f(x)", listOf(0f to 0f, 1f to 0.3f, 2f to 1.2f, 3f to 2.8f))),
            NoteBlock(NoteElement.Summary, items = listOf("المشتقة = ميل المماس", "\\((e^x)' = e^x\\)", "قابلية الاشتقاق ⇐ الاتصال")),
        ),
        terms = listOf(
            GlossaryTerm("المشتقة", "נגזרת"),
            GlossaryTerm("النهاية", "גבול"),
            GlossaryTerm("المماس", "משיק"),
            GlossaryTerm("متصلة", "רציפה"),
        ),
    )

    /**
     * Hebrew physics in the study-notes style: theory and formulas in one column, a worked example in
     * the other, numbered boxed formulas referenced by later steps, a legend of every symbol with its unit.
     */
    fun hebrewPhysics() = NoteDocument(
        elements = listOf(
            NoteBlock(NoteElement.Title, text = "קינמטיקה וחיכוך"),
            NoteBlock(
                NoteElement.Columns,
                columns = listOf(
                    listOf(
                        NoteBlock(NoteElement.Heading, text = "תנועה בתאוצה קבועה"),
                        NoteBlock(
                            NoteElement.Derivation,
                            steps = listOf(
                                NoteStep(math = "a = \\frac{\\Delta v}{\\Delta t}"),
                                NoteStep(math = "a\\Delta t = \\Delta v"),
                                NoteStep(math = "a t = V_t - V_0"),
                                NoteStep(math = "V_t = V_0 + a t"),
                            ),
                        ),
                        NoteBlock(
                            NoteElement.VariableLegend,
                            rows = listOf(
                                listOf("V_t", "מהירות בזמן מסוים", "m/s"),
                                listOf("V_0", "מהירות התחלתית", "m/s"),
                                listOf("a", "תאוצה", "m/s^2"),
                                listOf("t", "זמן", "s"),
                            ),
                        ),
                        NoteBlock(NoteElement.NumberedFormula, math = "x = x_0 + V_0 t + \\frac{1}{2} a t^2"),
                    ),
                    listOf(
                        NoteBlock(
                            NoteElement.WorkedExample,
                            text = "גוף יוצא ממנוחה בתאוצה קבועה.",
                            rows = listOf(listOf("V_0", "0", "m/s"), listOf("a", "2", "m/s^2")),
                            parts = listOf(
                                NotePart("מהירותו אחרי 5 שניות? (לפי ①)", listOf("V_t = V_0 + a t", "V_t = 0 + 2\\cdot 5"), "V_t = 10\\,(m/s)"),
                                NotePart("הדרך שעבר? (לפי ②)", listOf("x = \\frac{1}{2}\\cdot 2\\cdot 5^2"), "x = 25\\,(m)"),
                            ),
                        ),
                    ),
                ),
            ),
            NoteBlock(NoteElement.Heading, text = "חיכוך"),
            NoteBlock(
                NoteElement.Columns,
                columns = listOf(
                    listOf(
                        NoteBlock(NoteElement.Definition, head = "חיכוך סטטי", text = "הכוח שמונע מגוף להתחיל לנוע, עד ערך מקסימלי."),
                        NoteBlock(NoteElement.NumberedFormula, math = "f_s \\le \\mu_s N"),
                        NoteBlock(
                            NoteElement.Diagram,
                            head = "תרשים כוחות",
                            diagram = DiagramKinds.freeBody(listOf(DiagramKinds.Force("N", 90f), DiagramKinds.Force("mg", 270f), DiagramKinds.Force("F", 0f), DiagramKinds.Force("f", 180f))),
                        ),
                        NoteBlock(NoteElement.StarNote, text = "החיכוך הקינטי קטן מהמקסימום הסטטי: \\(\\mu_k < \\mu_s\\)"),
                    ),
                    listOf(
                        NoteBlock(
                            NoteElement.WorkedExample,
                            text = "ארגז על רצפה, מושכים בכוח אופקי.",
                            rows = listOf(listOf("m", "5", "kg"), listOf("\\mu_s", "0.5", ""), listOf("g", "10", "m/s^2")),
                            parts = listOf(
                                NotePart("הכוח הנורמלי?", listOf("N = mg = 5\\cdot 10"), "N = 50\\,(N)"),
                                NotePart("החיכוך הסטטי המקסימלי? (לפי ③)", listOf("f_{max} = \\mu_s N = 0.5\\cdot 50"), "f_{max} = 25\\,(N)"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        terms = listOf(GlossaryTerm("תאוצה", "acceleration"), GlossaryTerm("חיכוך סטטי", "static friction"), GlossaryTerm("כוח נורמלי", "normal force")),
    )
}
