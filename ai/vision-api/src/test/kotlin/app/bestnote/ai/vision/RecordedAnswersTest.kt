package app.bestnote.ai.vision

import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.RectPt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Model answers for whiteboards (Arabic, English, maths) in the shapes real providers return:
 * content in the "wrong" field, prose around fenced JSON, cut-off output, loose types, bare arrays.
 * Every one used to end in "Nothing on this photo could be read"; now each keeps its content.
 */
class RecordedAnswersTest {
    private fun answer(name: String) = javaClass.getResource("/answers/$name")!!.readText()

    private fun map(name: String): Pair<AiDocument, DocumentMapper.Result> {
        val doc = AiDocumentParser.parse(answer(name)).document
        var n = 0
        return doc to DocumentMapper({ "b${n++}" }).map(doc)
    }

    private fun DocumentMapper.Result.built() = blocks.map { it.build(RectPt(0f, 0f, 100f, 100f)) }

    @Test
    fun `content in alternate fields is salvaged instead of dropping every block`() {
        // The owner's failure: optional-only schema, so no `text`, no doc title, latex in `text`.
        val (doc, mapped) = map("optional_fields_only.json")
        assertNull(doc.title)
        assertEquals(mapped.dropReasons.toString(), 0, mapped.dropped)
        val built = mapped.built()
        assertEquals(7, built.size)
        assertEquals("المشتقات", (built[0] as PageBlock.Heading).text)
        assertEquals("ميل المماس للمنحنى عند نقطة", (built[1] as PageBlock.Callout).body)
        assertTrue((built[2] as PageBlock.Math).latex.startsWith("f'(x) = \\lim"))
        assertEquals(3, (built[3] as PageBlock.Bullets).items.size)
        assertEquals("(fg)' = f'g + fg'", (built[4] as PageBlock.Math).latex)
        assertEquals("h ≠ 0 !!", (built[5] as PageBlock.MarginNote).text)
        // Diagram with no size: inferred from its points.
        assertTrue(built[6] is PageBlock.Diagram)
    }

    @Test
    fun `json after prose and inside a fence`() {
        val parsed = AiDocumentParser.parse(answer("prose_and_fence.txt"))
        assertTrue(parsed.notes.any { "stripped" in it })
        val (_, mapped) = map("prose_and_fence.txt")
        val built = mapped.built()
        assertEquals(0, mapped.dropped)
        assertEquals("Newton's second law", (built[0] as PageBlock.Heading).text)
        assertEquals(CalloutTone.Theorem, (built[2] as PageBlock.Callout).tone)
        assertEquals("\\vec{F} = m\\vec{a}", (built[3] as PageBlock.Math).latex)
        assertEquals(2, (built[4] as PageBlock.Proof).steps.size)
    }

    @Test
    fun `cut-off arabic answer keeps every complete block`() {
        val parsed = AiDocumentParser.parse(answer("truncated_arabic.json"))
        assertTrue(parsed.notes.toString(), parsed.notes.any { "truncated" in it })
        val doc = parsed.document
        assertEquals("التكامل المحدد", doc.title)
        assertEquals(4, doc.blocks.size)
        // The proof keeps its two finished steps; the third was cut mid-string.
        assertEquals(2, doc.blocks[3].steps!!.size)
        assertEquals("\\int_0^1 x^2\\,dx", doc.blocks[3].steps!![0].latex)
        val (_, mapped) = map("truncated_arabic.json")
        assertEquals(mapped.dropReasons.toString(), 0, mapped.dropped)
    }

    @Test
    fun `loose types from an openapi schema`() {
        val (doc, mapped) = map("openapi_loose_types.json")
        assertNull(doc.title)
        val math = doc.blocks[0]
        assertEquals(2, math.order)
        assertEquals(true, math.boxed)
        assertEquals(listOf(0.1f, 0.4f, 0.5f, 0.5f), math.sourceBox)
        assertEquals(0, doc.blocks[1].order)
        assertEquals(1, doc.blocks[1].level)
        // Diagram with size [0,0] gets one from its points; the empty paragraph is the only drop.
        assertEquals(listOf("block 4 (paragraph): no text"), mapped.dropReasons)
        assertEquals(4, mapped.blocks.size)
    }

    @Test
    fun `bare array with synonyms and a plain string`() {
        val (_, mapped) = map("root_array.json")
        val built = mapped.built()
        assertEquals(0, mapped.dropped)
        assertTrue(built[0] is PageBlock.Heading)
        assertTrue(built[1] is PageBlock.Paragraph)
        assertTrue(built[2] is PageBlock.Math)
        assertEquals("Examples: n^2 + n = O(n^2)", (built[3] as PageBlock.Paragraph).text)
    }

    @Test
    fun `well formed mixed arabic and english board maps fully`() {
        val (_, mapped) = map("full_mixed_board.json")
        assertEquals(0, mapped.dropped)
        val built = mapped.built()
        assertEquals(9, built.size) // title + 8
        val note = built.filterIsInstance<PageBlock.MarginNote>().single()
        assertNotNull(note.anchor)
    }

    @Test
    fun `nothing usable fails with the reasons`() {
        val doc = AiDocumentParser.parse("""{"blocks":[{"type":"math"},{"type":"diagram"}]}""").document
        try {
            DocumentMapper({ "x" }).map(doc).requireBlocks()
            fail()
        } catch (e: VisionError.BadResponse) {
            assertTrue(e.message, "block 0 (math): no formula" in e.message!!)
            assertTrue(e.message, "block 1 (diagram): no diagram" in e.message!!)
        }
    }

    @Test
    fun `missing closing braces are repaired, forbidden latex is dropped`() {
        val doc = AiDocument(blocks = listOf(AiBlock(type = "math", latex = "\\frac{a}{b"), AiBlock(type = "math", latex = "\\input{x}")))
        val mapped = DocumentMapper({ "x" }).map(doc)
        assertEquals("\\frac{a}{b}", (mapped.built().single() as PageBlock.Math).latex)
        assertEquals(listOf("block 1 (math): invalid LaTeX"), mapped.dropReasons)
    }

    @Test
    fun `single backslash latex is restored`() {
        // Valid JSON, but "\f" and "\n" decode to control characters.
        val doc = AiDocumentParser.parse("{\"blocks\":[{\"type\":\"math\",\"latex\":\"\\frac{1}{2} \\neq \\theta\"}]}").document
        assertEquals("\\frac{1}{2} \\neq \\theta", doc.blocks.single().latex)
    }

    @Test
    fun `repair returns null when nothing was complete`() {
        assertNull(AiDocumentParser.repairTruncated("{\"title\":\"abc"))
        assertEquals("""{"a":[{"b":1}]}""", AiDocumentParser.repairTruncated("""{"a":[{"b":1},{"c":"""))
    }

    @Test
    fun `truncated answer with nothing complete is a Truncated error`() {
        try {
            AiDocumentParser.answer("{\"title\":\"Der", truncated = true)
            fail()
        } catch (e: VisionError.Truncated) {
            assertEquals("{\"title\":\"Der", e.raw)
        }
    }

    @Test
    fun `not json at all`() {
        try {
            AiDocumentParser.parse("I can't read this board, sorry.")
            fail()
        } catch (e: VisionError.BadResponse) {
            assertEquals("I can't read this board, sorry.", e.raw)
        }
    }

    @Test
    fun `schema requires the fields the mapper needs`() {
        val block = AiSchema.document().let { root ->
            assertEquals("[\"title\",\"blocks\"]", root["required"].toString())
            (root["properties"] as kotlinx.serialization.json.JsonObject)["blocks"].toString()
        }
        assertTrue(block.contains("\"required\":[\"type\",\"order\",\"text\"]"))
        val closed = AiSchema.document(AiSchema.Dialect.Closed).toString()
        assertEquals(5, Regex("\"additionalProperties\":false").findAll(closed).count())
        val openApi = AiSchema.document(AiSchema.Dialect.OpenApi).toString()
        assertFalse(openApi.contains("additionalProperties"))
        assertTrue(openApi.contains("\"propertyOrdering\":[\"type\",\"order\",\"text\""))
    }
}

class TranscriptLinesTest {
    @Test
    fun `transcription answer becomes lines in reading order`() {
        val doc = AiDocumentParser.parse(
            """{"title":"","blocks":[
              {"type":"paragraph","order":1,"text":"والمشتقة هي ميل المماس"},
              {"type":"paragraph","order":0,"text":"تعريف: الدالة المتصلة"},
              {"type":"math","order":2,"text":"f'(x) = 2x","latex":"f'(x) = 2x"},
              {"type":"paragraph","order":3,"text":"see p. 12\nfor proof"}]}""",
        ).document
        assertEquals(listOf("تعريف: الدالة المتصلة", "والمشتقة هي ميل المماس", "f'(x) = 2x", "see p. 12", "for proof"), doc.transcriptLines())
    }
}
