package app.bestnote.ai.vision

import app.bestnote.core.model.LayoutTemplate
import app.bestnote.engine.layout.AiInstructions
import app.bestnote.engine.layout.DetailLevel
import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.NotebookGlossary
import app.bestnote.engine.layout.TermsStyle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteWireTest {
    private val classic = NoteTemplates.byId("classic")
    private val study = NoteTemplates.byId(NoteTemplates.STUDY_COLUMNS)

    @Test fun `compact answer parses into elements`() {
        val answer = """
            ```json
            {"elements":[
              {"e":"Title","t":"المشتقة"},
              {"e":"Definition","h":"المشتقة","t":"ميل المماس \(f\)"},
              {"e":"KeyResult","m":"\frac{d}{dx}x^n = nx^{n-1}"},
              {"e":"StepSolution","steps":[{"s":"نعوّض","why":"تعريف"},{"m":"f'(x)=e^x"}]},
              {"e":"Table","rows":[["a","b"],["1","2"]]},
              {"e":"Glossary","terms":[["المشتقة","נגזרת"]]},
              {"e":"MarginRemark","t":"!","target":2},
              {"e":"Diagram","h":"forces","fbd":[["N",90],["mg","270"]]},
              {"e":"WorkedExample","t":"box","rows":[["m","5","kg"]],"parts":[{"q":"N?","s":["N=mg"],"r":"N=50\\,(N)"}]},
              {"e":"Columns","cols":[[{"e":"Formula","m":"a=b"}],[{"e":"StarNote","t":"x"}]]}
            ],"terms":[["النهاية","גבול"]]}
            ```
        """.trimIndent()
        val doc = NoteWire.parse(answer)
        assertEquals(10, doc.elements.size)
        assertEquals("\\frac{d}{dx}x^n = nx^{n-1}", doc.elements[2].math) // single-backslash LaTeX kept
        assertEquals("تعريف", doc.elements[3].steps[0].why)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), doc.elements[4].rows)
        assertEquals(GlossaryTerm("المشتقة", "נגזרת"), doc.elements[5].terms.single())
        assertEquals(2, doc.elements[6].target)
        assertNotNull(doc.elements[7].diagram)
        assertEquals("N=50\\,(N)", doc.elements[8].parts.single().result)
        assertEquals(2, doc.elements[9].columns.size)
        assertEquals(listOf(GlossaryTerm("النهاية", "גבול")), doc.terms)
        assertEquals(2, doc.allTerms().size)
    }

    @Test fun `encode then parse is lossless for the samples`() {
        for (sample in listOf(NoteSamples.arabicCalculus(), NoteSamples.hebrewPhysics())) {
            val back = NoteWire.parse(NoteWire.encode(sample))
            assertEquals(sample.elements.map { it.element }, back.elements.map { it.element })
            assertEquals(sample.flatten().map { it.text to it.math }, back.flatten().map { it.text to it.math })
            assertEquals(sample.terms, back.terms)
        }
    }

    @Test fun `the old block format still parses`() {
        val old = """{"title":"Inclined plane","blocks":[
            {"type":"definition","order":0,"title":"Normal force","text":"push of a surface"},
            {"type":"math","id":"k","order":1,"latex":"a = g\\sin\\theta","boxed":true},
            {"type":"margin_note","order":2,"annotates":"k","text":"check"},
            {"type":"proof","order":3,"steps":[{"latex":"N = mg\\cos\\theta","because":"no motion"}]}]}"""
        val doc = NoteWire.parse(old)
        assertEquals(listOf(NoteElement.Title, NoteElement.Definition, NoteElement.KeyResult, NoteElement.MarginRemark, NoteElement.StepSolution), doc.elements.map { it.element })
        assertEquals(2, doc.elements[3].target)
        assertEquals("no motion", doc.elements[4].steps.single().why)
    }

    @Test fun `unknown element names fall back and truncation is repaired`() {
        val doc = NoteWire.parse("""{"elements":[{"e":"key result","m":"x=1"},{"e":"whatever","t":"words"},{"e":"Formula","m":"y=2"},{"e":"Paragraph","t":"cut of""")
        assertEquals(listOf(NoteElement.KeyResult, NoteElement.Paragraph, NoteElement.Formula), doc.elements.map { it.element })
    }

    @Test fun `schema offers only the template's elements and their fields`() {
        val t = classic.copy(elements = setOf(NoteElement.Title, NoteElement.Paragraph, NoteElement.Formula), ai = AiInstructions(glossary = false))
        val s = NoteSchema.document(t, AiSchema.Dialect.Closed)
        val el = s["properties"]!!.jsonObject["elements"]!!.jsonObject["items"]!!.jsonObject
        val enum = (el["properties"]!!.jsonObject["e"]!!.jsonObject["enum"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(listOf("Title", "Paragraph", "Formula"), enum)
        val props = el["properties"]!!.jsonObject.keys
        assertTrue("m" in props)
        assertFalse("rows" in props || "cols" in props || "steps" in props || "target" in props)
        assertFalse("terms" in s["properties"]!!.jsonObject.keys)
        // Columns nest one level of elements.
        val st = NoteSchema.document(study, AiSchema.Dialect.OpenApi).toString()
        assertTrue(st.contains("\"cols\"") && st.contains("\"OBJECT\""))
    }

    @Test fun `prompt is built from the template's AI instructions, the user's note verbatim`() {
        val note = "اكتب بالعربية واترك المصطلحات بالعبرية — keep units in SI!"
        val t = classic.copy(
            elements = setOf(NoteElement.Title, NoteElement.Definition, NoteElement.KeyResult, NoteElement.Glossary),
            ai = AiInstructions(notesLanguage = "ar", termsLanguage = "he", termsStyle = TermsStyle.InParentheses, detail = DetailLevel.Summary, stepByStep = false, glossary = true, note = note),
        )
        val p = NotePrompts.system(NotesRequest(t, rtl = true))
        assertTrue(p.startsWith("You are an elite, meticulous student"))
        assertTrue("note verbatim", p.contains(note))
        assertTrue(p.indexOf(note) > p.indexOf("highest priority"))
        assertTrue(p.contains("write all notes in Arabic"))
        assertTrue(p.contains("Hebrew term in parentheses"))
        assertTrue(p.contains("short summary"))
        assertFalse(p.contains("step by step"))
        assertTrue(p.contains("Definition: h = the term"))
        assertFalse("only allowed elements", p.contains("WorkedExample") || p.contains("StepSolution"))
        assertTrue(p.contains("terms = [[term as in the notes, term in Hebrew]]"))
        assertTrue(p.contains("right to left"))
        val same = NotePrompts.system(NotesRequest(classic))
        assertTrue(same.contains("the board's own") && same.contains("Never translate"))
        assertFalse(same.contains("user's instruction"))
    }

    @Test fun `follow-up prompt sends the page's elements and asks for one merged list`() {
        val current = NoteDocument(listOf(app.bestnote.engine.layout.NoteBlock(NoteElement.Definition, head = "גבול", text = "x")))
        val user = NotePrompts.user(NotesRequest(classic, current = current))
        assertTrue(user.contains(NoteWire.encode(current)))
        assertTrue(user.contains("ONE merged") && user.contains("remove duplicates") && user.contains("teaching order"))
        assertEquals(NotePrompts.USER, NotePrompts.user(NotesRequest(classic)))
    }

    @Test fun `merge removes a definition photographed twice`() {
        val a = NoteSamples.arabicCalculus()
        val merged = NoteMerge.merge(a, DemoVisionProvider.followUp(a))
        assertEquals(a.elements.size + 3, merged.elements.size)
        assertEquals(1, merged.elements.count { it.element == NoteElement.Definition })
        assertEquals(5, NotebookGlossary().merge(merged.terms).first.terms.size)
    }

    @Test fun `demo provider answers in the element format, Arabic with Hebrew terms`() = runTest {
        val demo = DemoVisionProvider(latencyMs = 0)
        val doc = demo.writeNotes(VisionImage(ByteArray(0), "image/jpeg"), NotesRequest(classic))
        assertTrue(doc.terms.any { it.translation == "נגזרת" })
        assertTrue(doc.flatten().any { it.math?.contains("x^{n-1}") == true })
        val physics = demo.writeNotes(VisionImage(ByteArray(0), "image/jpeg"), NotesRequest(study))
        assertTrue(physics.elements.any { it.element == NoteElement.Columns })
        val again = demo.writeNotes(VisionImage(ByteArray(0), "image/jpeg"), NotesRequest(classic, current = doc))
        assertTrue(again.elements.size > doc.elements.size)
    }

    @Test fun `smart board record keeps groups and accumulates the glossary`() {
        val g = SmartBoardGroup("classic", listOf("page-1", "page-2"), NoteSamples.arabicCalculus(), setOf("t1"), setOf(SmartBoardRecord.strokeKey(1f, 2f, 3, 0xFF000000.toInt())))
        val r = SmartBoardRecord().withInsert(g).withInsert(g.copy(pageIds = listOf("page-3")))
        assertEquals(4, r.glossary.terms.size)
        val back = SmartBoardRecord.decode(r.encode())
        assertEquals(2, back.groups.size)
        assertEquals(g.pageIds, back.groupFor("page-2")!!.pageIds)
        assertEquals(g.notes.elements.map { it.element }, back.groups[0].notes.elements.map { it.element })
        assertEquals(g.strokeKeys, back.groups[0].strokeKeys)
        assertEquals(1, back.prune(setOf("page-3")).groups.size)
        assertEquals(0, SmartBoardRecord.decode("not json".encodeToByteArray()).groups.size)
    }

    /**
     * Token cost, old block format vs compact elements, for the same notes (the demo's English
     * lecture): system prompt + schema sent with every request, and the model's answer.
     */
    @Test fun `compact format costs far fewer tokens than the old block format`() {
        val oldPrompt = Prompts.board(BoardRequest(LayoutTemplate.LectureSummary)) + AiSchema.document(AiSchema.Dialect.Closed).toString()
        val newPrompt = NotePrompts.system(NotesRequest(classic)) + NoteSchema.document(classic, AiSchema.Dialect.Closed).toString()
        val oldDoc = DemoVisionProvider.ENGLISH
        val oldOutput = Json.encodeToString(AiDocument.serializer(), oldDoc)
        // A real model fills the required fields and the hints it is asked for (order, semantic, ink colour, source box).
        val oldTypical = Json.encodeToString(
            AiDocument.serializer(),
            oldDoc.copy(blocks = oldDoc.blocks.mapIndexed { i, b -> b.copy(text = b.text ?: "", semantic = b.semantic ?: "regular", inkColor = b.inkColor ?: "black", sourceBox = listOf(0.1f, 0.1f * i, 0.9f, 0.1f * i + 0.08f), lang = "en") }),
        )
        val newOutput = NoteWire.encode(LegacyNotes.fromAiDocument(oldDoc))
        val p0 = tokens(oldPrompt)
        val p1 = tokens(newPrompt)
        val o0 = tokens(oldTypical)
        val o1 = tokens(newOutput)
        println("TOKENS prompt+schema old=$p0 new=$p1 (${pct(p0, p1)}); output old=$o0 (bare ${tokens(oldOutput)}) new=$o1 (${pct(o0, o1)})")
        // FIG-LIB: the figure catalogue is budgeted on its own (≤ 650 tokens); the rest stays well under the old format.
        val catalogue = tokens(NotePrompts.FIGURE_KINDS)
        println("TOKENS figure catalogue=$catalogue")
        assertTrue(catalogue <= 650)
        assertTrue(p1 - catalogue < p0 * 0.6)
        assertTrue(o1 < o0 * 0.7)
    }

    /** ≈ tokens: 4 characters per token for ASCII, one per 2 characters of other scripts. */
    private fun tokens(s: String): Int = (s.count { it.code < 128 } / 4.0 + s.count { it.code >= 128 } / 2.0).toInt()

    private fun pct(a: Int, b: Int) = "-${100 - b * 100 / a}%"

    @Test fun emptyElementsAreDroppedAndMarginTargetsFollow() {
        // Live DeepSeek answer shape: an empty Summary and Glossary, a remark pointing past them.
        val doc = NoteWire.answer("""{"elements":[{"e":"Title","t":"T"},{"e":"Summary"},{"e":"Formula","m":"x=1"},{"e":"Glossary"},{"e":"MarginRemark","t":"see","target":2}]}""")
        assertEquals(listOf(NoteElement.Title, NoteElement.Formula, NoteElement.MarginRemark), doc.elements.map { it.element })
        assertEquals(1, doc.elements.last().target)
    }
}
