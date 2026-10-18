package app.bestnote.ai.vision

import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.StyleTemplates
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RefineEngineTest {
    private val doc = NoteDocument(
        listOf(
            NoteBlock(NoteElement.Title, text = "Kinematics"),
            NoteBlock(NoteElement.Heading, text = "Velocity"),
            NoteBlock(NoteElement.Paragraph, text = "v = dx/dt"),
            NoteBlock(NoteElement.MarginRemark, text = "!", target = 2),
            NoteBlock(NoteElement.Paragraph, text = "Graph two"),
        ),
        terms = listOf(GlossaryTerm("velocity", "speed")),
    )

    @Test fun `replace delete insert apply by original ids`() {
        val answer = """{"ops":[{"op":"delete","id":1},{"op":"replace","id":4,"e":{"e":"Paragraph","t":"Graph 2"}},{"op":"insert","after":2,"e":{"e":"Note","t":"missing step"}}]}"""
        val r = RefineEngine.apply(answer, doc)
        assertEquals(3, r.applied)
        assertEquals(listOf("Kinematics", "v = dx/dt", "missing step", "!", "Graph 2"), r.doc.elements.map { it.text })
        // The margin remark still points at "v = dx/dt", now at index 1.
        assertEquals(1, r.doc.elements[3].target)
        assertEquals(doc.terms, r.doc.terms)
    }

    @Test fun `inserts after the same element keep their order`() {
        val answer = """{"ops":[{"op":"insert","after":0,"e":{"e":"Note","t":"a"}},{"op":"insert","after":0,"e":{"e":"Note","t":"b"}},{"op":"insert","after":-1,"e":{"e":"Note","t":"top"}}]}"""
        val r = RefineEngine.apply(answer, doc)
        assertEquals(listOf("top", "Kinematics", "a", "b", "Velocity"), r.doc.elements.take(5).map { it.text })
    }

    @Test fun `invalid ops are skipped and reported, valid ones applied`() {
        val answer = """{"ops":[{"op":"delete","id":42},{"op":"explode"},{"op":"replace","id":0,"e":{"e":"Title","t":"Motion"}}]}"""
        val r = RefineEngine.apply(answer, doc)
        assertEquals(1, r.applied)
        assertEquals(2, r.skipped.size)
        assertEquals("Motion", r.doc.elements[0].text)
    }

    @Test fun `nothing usable is a bad response`() {
        expectBad("""{"ops":[{"op":"delete","id":99}]}""")
        expectBad("I cannot do that")
        expectBad("""{"ops":[{"op":"delete","id":0},{"op":"delete","id":1},{"op":"delete","id":2},{"op":"delete","id":3},{"op":"delete","id":4}]}""")
    }

    @Test fun `selection scopes the edit`() {
        val answer = """{"ops":[{"op":"delete","id":0},{"op":"replace","id":4,"e":{"e":"Paragraph","t":"Graph 2"}}]}"""
        val r = RefineEngine.apply(answer, doc, selection = setOf(4))
        assertEquals(1, r.applied)
        assertEquals("Kinematics", r.doc.elements[0].text)
        assertEquals("Graph 2", r.doc.elements.last().text)
    }

    @Test fun `full list replaces the page, or only the selection`() {
        val whole = RefineEngine.apply("""{"elements":[{"e":"Title","t":"A"},{"e":"Paragraph","t":"B"}]}""", doc)
        assertEquals(listOf("A", "B"), whole.doc.elements.map { it.text })
        val part = RefineEngine.apply("""{"elements":[{"e":"Paragraph","t":"X"},{"e":"Paragraph","t":"Y"}]}""", doc, selection = setOf(1, 2))
        assertEquals(listOf("Kinematics", "X", "Y", "!", "Graph two"), part.doc.elements.map { it.text })
    }

    @Test fun `fenced answer and colour font terms ops`() {
        val answer = "```json\n" + """{"ops":[{"op":"color","role":"Heading","color":"#FF0000"},{"op":"font","script":"arabic","font":"aref-ruqaa"},{"op":"terms","terms":[["velocity","سرعة"]]}]}""" + "\n```"
        val r = RefineEngine.apply(answer, doc, fonts = setOf("aref-ruqaa", "caveat"))
        assertEquals(3, r.applied)
        assertEquals(0xFFFF0000L, r.look.colors[NoteRole.Heading])
        assertEquals(0xFFFF0000L, r.look.colors[NoteRole.Subheading])
        assertEquals("aref-ruqaa", r.look.fonts[FontSlot.Arabic])
        assertEquals("سرعة", r.doc.terms.single().translation)
        val look = r.look.applyTo(StyleTemplates.Classic)
        assertEquals(0xFFFF0000L, look.role(NoteRole.Heading).color)
        assertEquals("aref-ruqaa", look.arabicFont.id)
        assertEquals(StyleTemplates.Classic.role(NoteRole.Title), look.role(NoteRole.Title))
    }

    @Test fun `unknown font is rejected`() {
        expectBad("""{"ops":[{"op":"font","font":"comic-sans"}]}""", fonts = setOf("caveat"))
    }

    @Test fun `user turn lists ids and selection`() {
        val turn = RefineEngine.userTurn(doc, "make headings red", setOf(1))
        assertTrue(turn.contains("\"id\":1"))
        assertTrue(turn.contains("Selected ids: 1"))
        assertTrue(turn.endsWith("Request: make headings red"))
    }

    @Test fun `refine calls the provider with text only`() = runBlocking {
        var asked: String? = null
        val fake = object : VisionProvider by DemoVisionProvider() {
            override suspend fun completeText(system: String, turns: List<ChatTurn>, json: Boolean): String {
                asked = turns.single().text
                return """{"ops":[{"op":"delete","id":4}]}"""
            }
        }
        val r = RefineEngine.refine(fake, doc, "remove the second graph")
        assertTrue(asked!!.contains("remove the second graph"))
        assertEquals(4, r.doc.elements.size)
    }

    @Test fun `local commands need no AI`() {
        val templates = listOf("classic" to "Classic", "cornell" to "Cornell")
        val fonts = listOf("caveat" to "Caveat", "aref-ruqaa" to "Aref Ruqaa")
        assertEquals(LocalRefine.Template("cornell"), LocalRefineCommands.parse("switch template to Cornell", templates, fonts))
        assertEquals(LocalRefine.Template("classic"), LocalRefineCommands.parse("القالب classic", templates, fonts))
        assertEquals(LocalRefine.Font(FontSlot.Arabic, "aref-ruqaa"), LocalRefineCommands.parse("font Aref Ruqaa", templates, fonts, FontSlot.Arabic))
        assertEquals(LocalRefine.Undo, LocalRefineCommands.parse("Undo", templates, fonts))
        assertEquals(LocalRefine.Undo, LocalRefineCommands.parse("تراجع", templates, fonts))
        val red = LocalRefineCommands.parse("make headings red", templates, fonts) as LocalRefine.Color
        assertEquals(0xFFD9462BL, red.color)
        assertTrue(NoteRole.Heading in red.roles)
        val ar = LocalRefineCommands.parse("العناوين بالأحمر", templates, fonts) as LocalRefine.Color
        assertTrue(NoteRole.Heading in ar.roles)
        assertNull(LocalRefineCommands.parse("translate the terms to Arabic", templates, fonts))
        assertNull(LocalRefineCommands.parse("add the missing step in example 2 and make headings red please", templates, fonts))
    }

    @Test fun `recorded DeepSeek answer applies`() {
        // Live DeepSeek answer (AI-13) to "make headings red, translate the terms to Arabic and remove the second graph".
        val answer = """{"ops":[{"op":"color","role":"Heading","color":"#FF0000"},{"op":"terms","terms":[["velocity","السرعة"],["position","الموضع"]]},{"op":"delete","id":4}]}"""
        val r = RefineEngine.apply(answer, doc)
        assertEquals(3, r.applied)
        assertEquals(4, r.doc.elements.size)
        assertEquals("السرعة", r.doc.terms.first().translation)
        assertEquals(0xFFFF0000L, r.look.colors[NoteRole.Heading])
    }

    private fun expectBad(answer: String, fonts: Set<String> = emptySet()) {
        try {
            RefineEngine.apply(answer, doc, fonts = fonts)
            fail("expected BadResponse for $answer")
        } catch (_: VisionError.BadResponse) {
        }
    }
}

class SmartBoardPhotosRecordTest {
    @Test fun `group photos survive encode and decode, old records have none`() {
        val g = SmartBoardGroup("classic", listOf("p1"), NoteDocument(emptyList()), photos = listOf("assets/a.jpg", "assets/b.jpg"))
        val back = SmartBoardRecord.decode(SmartBoardRecord().withInsert(g).encode())
        assertEquals(listOf("assets/a.jpg", "assets/b.jpg"), back.groups.single().photos)
        val old = SmartBoardRecord.decode(SmartBoardRecord().withInsert(g.copy(photos = emptyList())).encode())
        assertTrue(old.groups.single().photos.isEmpty())
    }
}
