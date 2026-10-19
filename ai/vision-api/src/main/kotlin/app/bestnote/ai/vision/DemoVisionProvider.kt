package app.bestnote.ai.vision

import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.WritingScript
import kotlinx.coroutines.delay

/**
 * Offline demo: answers every board with a prepared physics lecture (an inclined plane), in the
 * shape a real provider returns, so the whole Smart Board flow — reading order, ruler geometry,
 * colour meaning, handwriting — can be tried without an API key or a network. Arabic when the
 * request says the board is right to left.
 */
class DemoVisionProvider(private val latencyMs: Long = 900) : VisionProvider {
    override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument {
        delay(latencyMs)
        return when {
            request.language == "Hebrew" -> HEBREW
            request.language == "Arabic" || request.language == "ar" -> ARABIC
            request.language == "English" -> ENGLISH
            request.rtl -> ARABIC
            else -> ENGLISH
        }
    }

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?): AiDocument {
        delay(latencyMs)
        return AiDocument(blocks = listOf(AiBlock(type = "paragraph", text = "Tidied note (demo)")))
    }

    override suspend fun transcribe(selection: VisionImage, language: String?): AiDocument {
        delay(latencyMs)
        val line = if (language?.startsWith("ar", ignoreCase = true) == true || language == "Arabic") "ملاحظة مكتوبة بخط اليد (تجريبي)" else "Handwritten note (demo)"
        return AiDocument(blocks = listOf(AiBlock(type = "paragraph", order = 0, text = line)))
    }

    override suspend fun readEquation(selection: VisionImage): AiDocument {
        delay(latencyMs)
        return AiDocument(blocks = listOf(AiBlock(type = "math", order = 0, latex = "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", text = "")))
    }

    /**
     * Template first: answers in the compact element format (encoded and parsed back, exactly like a
     * real answer): Arabic calculus notes with Hebrew terms and maths, or Hebrew physics study notes
     * for a template with columns. A follow-up photo adds a second-derivative section and repeats one
     * element, so the merge's de-duplication shows.
     */
    override suspend fun writeNotes(image: VisionImage, request: NotesRequest): NoteDocument {
        delay(latencyMs)
        val t = request.template
        val sample = when {
            NoteElement.Columns in t.elements -> NoteSamples.hebrewPhysics()
            else -> NoteSamples.arabicCalculus()
        }
        val current = request.current
        // REQ-002: a follow-up answers only what the new photo adds; the app merges it.
        val answer = if (current == null) sample else followUp(current)
        return NoteWire.answer(NoteWire.encode(answer))
    }

    companion object {
        /** What a second photo of the same board adds: one repeated element and a new section. */
        fun followUp(current: NoteDocument): NoteDocument {
            val script = WritingScript.dominant(current.elements.joinToString(" ") { it.words() })
            val repeat = current.elements.firstOrNull { it.element != NoteElement.Title }
            val more = if (script == WritingScript.Hebrew) {
                listOf(
                    NoteBlock(NoteElement.Heading, text = "תנועה בנפילה חופשית"),
                    NoteBlock(NoteElement.NumberedFormula, math = "V_t = g t"),
                    NoteBlock(NoteElement.StarNote, text = "התאוצה \\(g\\) זהה לכל הגופים"),
                )
            } else if (script == WritingScript.Arabic) {
                listOf(
                    NoteBlock(NoteElement.Heading, text = "المشتقة الثانية (נגזרת שנייה)"),
                    NoteBlock(NoteElement.KeyResult, math = "f''(x) = (f'(x))'"),
                    NoteBlock(NoteElement.StarNote, text = "إشارة \\(f''\\) تحدد التقعر"),
                )
            } else {
                listOf(NoteBlock(NoteElement.Heading, text = "Second derivative"), NoteBlock(NoteElement.KeyResult, math = "f''(x) = (f'(x))'"))
            }
            val term = if (script == WritingScript.Hebrew) GlossaryTerm("נפילה חופשית", "free fall") else GlossaryTerm("التقعر", "קעירות")
            return NoteDocument(listOfNotNull(repeat) + more, listOf(term))
        }

        /** A 30° slope with a block, its weight and the normal force. */
        private val SLOPE = AiDiagram(
            size = listOf(220f, 130f),
            shapes = listOf(
                AiShape("polygon", listOf(10f, 125f, 210f, 125f, 210f, 9.5f)),
                AiShape("rect", listOf(113f, 36f, 141f, 56f), rotation = -30f),
                AiShape("arrow", listOf(127f, 46f, 127f, 104f), color = "red"),
                AiShape("arrow", listOf(127f, 46f, 107f, 11f), color = "blue"),
                AiShape("label", listOf(132f, 104f), text = "mg", latex = true),
                AiShape("label", listOf(92f, 16f), text = "N", latex = true),
                AiShape("label", listOf(44f, 121f), text = "\\theta", latex = true),
            ),
        )

        val ENGLISH = AiDocument(
            title = "Inclined plane",
            blocks = listOf(
                AiBlock(type = "definition", id = "def", order = 0, title = "Normal force", text = "the push of a surface on a body, perpendicular to the surface."),
                AiBlock(type = "diagram", id = "fig", order = 1, diagram = SLOPE),
                AiBlock(type = "heading", order = 2, level = 2, text = "Forces along the slope"),
                AiBlock(type = "proof", id = "der", order = 3, steps = listOf(
                    AiProofStep(latex = "F_{\\parallel} = mg\\sin\\theta", because = "component of the weight"),
                    AiProofStep(latex = "N = mg\\cos\\theta", because = "no motion across the slope"),
                    AiProofStep(latex = "a = \\frac{F_{\\parallel}}{m} = g\\sin\\theta"),
                )),
                AiBlock(type = "math", id = "key", order = 4, latex = "a = g\\sin\\theta", boxed = true, semantic = "essential", inkColor = "red"),
                AiBlock(type = "margin_note", order = 5, annotates = "key", text = "check: at 30° a = g/2", inkColor = "green"),
                AiBlock(type = "bullets", order = 6, items = listOf("the mass cancels: every block slides alike", "friction adds μmg cos θ up the slope")),
            ),
        )

        val ARABIC = AiDocument(
            title = "المستوى المائل",
            blocks = listOf(
                AiBlock(type = "definition", id = "def", order = 0, title = "القوة العمودية", text = "دفع السطح للجسم عموديا على السطح"),
                AiBlock(type = "diagram", id = "fig", order = 1, diagram = SLOPE),
                AiBlock(type = "math", id = "key", order = 2, latex = "a = g\\sin\\theta", boxed = true, semantic = "essential"),
                AiBlock(type = "margin_note", order = 3, annotates = "key", text = "الكتلة لا تؤثر", inkColor = "green"),
                AiBlock(type = "bullets", order = 4, items = listOf("كل الأجسام تنزلق بنفس التسارع", "الاحتكاك يعاكس الحركة")),
            ),
        )

        /** A Hebrew lecture with English and maths mixed in, the way Israeli boards often are. */
        val HEBREW = AiDocument(
            title = "מישור משופע",
            blocks = listOf(
                AiBlock(type = "definition", id = "def", order = 0, title = "כוח נורמלי", text = "הכוח שמשטח מפעיל על גוף, בניצב למשטח.", lang = "he"),
                AiBlock(type = "diagram", id = "fig", order = 1, diagram = SLOPE),
                AiBlock(type = "heading", order = 2, level = 2, text = "כוחות לאורך המדרון", lang = "he"),
                AiBlock(type = "proof", id = "der", order = 3, steps = listOf(
                    AiProofStep(latex = "F_{\\parallel} = mg\\sin\\theta", because = "רכיב המשקל"),
                    AiProofStep(latex = "N = mg\\cos\\theta", because = "אין תנועה בניצב למדרון"),
                ), lang = "he"),
                AiBlock(type = "math", id = "key", order = 4, latex = "a = g\\sin\\theta", boxed = true, semantic = "essential"),
                AiBlock(type = "margin_note", order = 5, annotates = "key", text = "בדיקה: ב־30° מקבלים g/2", lang = "he"),
                AiBlock(type = "bullets", order = 6, items = listOf("המסה מצטמצמת: כל הגופים מחליקים באותה תאוצה", "the friction force μN acts up the slope"), lang = "he"),
            ),
        )
    }
}
