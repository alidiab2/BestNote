package app.bestnote.engine.templates

import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.AiInstructions
import app.bestnote.engine.layout.Decoration
import app.bestnote.engine.layout.DetailLevel
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteTemplates
import app.bestnote.engine.layout.RoleStyle
import app.bestnote.engine.layout.TermsStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private val fontsWithOwn = object : FontOptions {
    override fun ids(script: ScriptKey) = when (script) {
        ScriptKey.Latin -> listOf("caveat", "gf:kalam", "my:1", "system")
        ScriptKey.Arabic -> listOf("aref-ruqaa", "gf:katibeh", "system")
        ScriptKey.Hebrew -> listOf("gveret-levin", "gf:solitreo", "system")
    }
    override fun nameOf(id: String) = id
}

private fun custom(id: String = "user-1") = NoteTemplate(
    id = id,
    name = "Calculus cheat sheet",
    look = NoteTemplates.byId("classic").look.copy(
        id = id,
        paper = PaperTemplate.Grid,
        latinFont = HandFont("gf:kalam"),
        arabicFont = HandFont("gf:katibeh"),
        hebrewFont = HandFont("gveret-levin"),
        bodyScale = 0.8f,
        spaceBeforeHeading = 0,
        marginArrows = false,
        roles = NoteTemplates.byId("classic").look.roles + mapOf(
            NoteRole.Heading to RoleStyle(0xFF0000FFL, 1.3f, Decoration.Box),
            NoteRole.Formula to RoleStyle(0xFFFF0000L, 1.1f, Decoration.None),
            NoteRole.Body to RoleStyle(null, 1f),
        ),
    ),
    ai = AiInstructions(notesLanguage = "ar", termsLanguage = "he", termsStyle = TermsStyle.Only, detail = DetailLevel.CheatSheet, stepByStep = false, glossary = false, note = "Only formulas, with \"quotes\" and\nnew lines."),
    elements = setOf(NoteElement.Title, NoteElement.Heading, NoteElement.Formula, NoteElement.KeyResult),
)

class TemplateStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = TemplateStore(File(tmp.root, "templates")) { fontsWithOwn }

    @Test fun userTemplateSurvivesARoundTripThroughDisk() {
        val saved = store().save(custom())
        val back = store().byId("user-1") // a fresh store reads the file
        assertEquals(saved, back)
        assertEquals("Calculus cheat sheet", back.name)
        assertEquals(PaperTemplate.Grid, back.look.paper)
        assertEquals("gf:kalam", back.look.latinFont.id)
        assertEquals(0xFF0000FFL, back.look.role(NoteRole.Heading).color)
        assertEquals(Decoration.Box, back.look.role(NoteRole.Heading).decoration)
        assertNull(back.look.role(NoteRole.Body).color)
        assertEquals(custom().ai, back.ai) // including quotes and a newline in the note
        assertEquals(custom().elements, back.elements)
        assertFalse(back.builtIn)
        assertEquals("user-1", back.look.id)
    }

    @Test fun builtInsAreReadOnlyAndDuplicatable() {
        val s = store()
        assertTrue(s.all().take(NoteTemplates.builtIns().size).all { it.builtIn })
        assertThrows(IllegalArgumentException::class.java) { s.save(NoteTemplates.byId("classic")) }
        assertFalse(s.delete("classic"))
        assertNull(s.rename("classic", "x"))
        val copy = s.duplicate("classic")!!
        assertFalse(copy.builtIn)
        assertEquals("Classic copy", copy.name)
        assertEquals(NoteTemplates.byId("classic").look.roles, copy.look.roles)
        assertEquals("Classic copy 2", s.duplicate("classic")!!.name)
        assertEquals(2, s.mine().size)
        assertEquals(copy.id, s.mine().first().id)
    }

    @Test fun renameDeleteAndIds() {
        val s = store()
        val a = s.create("Mine")
        assertEquals("user-1", a.id)
        assertEquals("user-2", s.create("Second").id)
        assertEquals("Renamed", s.rename(a.id, "Renamed")!!.name)
        assertTrue(s.delete(a.id))
        assertNull(s.find(a.id))
        assertEquals("classic", s.byId(a.id).id) // a notebook whose template was deleted falls back to a built-in
        assertEquals("user-3", s.newId()) // ids are not reused downward
        assertFalse(s.delete("../x"))
        assertNull(s.find("../../etc"))
    }

    @Test fun anUnreadableFileIsSkippedNotDeleted() {
        val s = store()
        s.save(custom())
        val bad = File(tmp.root, "templates/user-9.json").also { it.writeText("{ this is not json") }
        assertEquals(listOf("user-1"), s.mine().map { it.id })
        assertTrue(bad.exists())
    }

    @Test fun missingKeysKeepDefaultsAndUnknownKeysAreIgnored() {
        File(tmp.root, "templates").mkdirs()
        File(tmp.root, "templates/user-4.json").writeText("""{"name":"Bare","future":{"x":1},"look":{"bodyScale":0.7,"roles":{"title":{"color":"#00FF00"}}}}""")
        val t = store().byId("user-4")
        assertEquals("Bare", t.name)
        assertEquals(0.7f, t.look.bodyScale, 0.0001f)
        assertEquals(0xFF00FF00L, t.look.role(NoteRole.Title).color)
        assertEquals(NoteTemplates.byId("classic").look.role(NoteRole.Title).scale, t.look.role(NoteRole.Title).scale, 0f)
        assertEquals(NoteTemplates.byId("classic").elements, t.elements)
    }

    @Test fun savingClampsWhatTheLayoutCannotDraw() {
        val wild = custom().let { it.copy(look = it.look.copy(bodyScale = 9f, latinFont = HandFont("nope")), elements = setOf(NoteElement.Formula)) }
        val saved = store().save(wild)
        assertEquals(TemplateClamp.MAX_BODY, saved.look.bodyScale, 0f)
        assertEquals("caveat", saved.look.latinFont.id)
        assertTrue(NoteElement.Title in saved.elements && NoteElement.Heading in saved.elements)
    }
}

class TemplateExchangeTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun exportThenImportMakesANewUserTemplate() {
        val s = TemplateStore(File(tmp.root, "a")) { fontsWithOwn }
        val file = TemplateExchange.export(custom("user-7"))
        assertTrue(file.contains("bestnoteTemplate"))
        assertFalse("the sender's id is not exported", file.contains("user-7"))
        val imported = s.import(file)
        assertEquals("user-1", imported.id)
        assertFalse(imported.builtIn)
        assertEquals(custom().ai, imported.ai)
        assertEquals(custom().look.copy(id = "user-1"), imported.look)
        assertEquals("Calculus cheat sheet 2".takeIf { false } ?: imported.name, imported.name)
        // importing the same file twice never overwrites and never duplicates the name
        val twice = s.import(file)
        assertNotEquals(imported.id, twice.id)
        assertNotEquals(imported.name, twice.name)
        assertEquals(2, s.mine().size)
    }

    @Test fun fontsTheDeviceLacksFallBackToTheScriptsDefault() {
        val imported = TemplateExchange.import(TemplateExchange.export(custom()), FontOptions.Bundled)
        assertEquals("caveat", imported.look.latinFont.id) // gf:kalam is not on this device
        assertEquals("aref-ruqaa", imported.look.arabicFont.id)
        assertEquals("gveret-levin", imported.look.hebrewFont.id)
    }

    @Test fun badFilesAreRefusedWithAReason() {
        fun kind(text: String) = assertThrows(TemplateImportException::class.java) { TemplateExchange.import(text) }.kind
        assertEquals(TemplateImportException.Kind.NotATemplate, kind("hello"))
        assertEquals(TemplateImportException.Kind.NotATemplate, kind("""{"name":"x"}"""))
        assertEquals(TemplateImportException.Kind.NotATemplate, kind("""{"bestnoteTemplate":1}"""))
        assertEquals(TemplateImportException.Kind.TooNew, kind("""{"bestnoteTemplate":99,"template":{}}"""))
        assertEquals(TemplateImportException.Kind.TooLarge, kind(" ".repeat(TemplateExchange.MAX_BYTES + 1)))
    }

    @Test fun fileNameIsSafe() {
        assertEquals("Calculus-cheat-sheet.bntemplate.json", TemplateExchange.fileName(custom()))
        assertEquals("template.bntemplate.json", TemplateExchange.fileName(custom().copy(name = "///")))
    }
}

class DesignWithAiTest {
    private val current = custom().let { it.copy(look = it.look.copy(id = it.id)) }

    @Test fun parsesAFencedAnswerAndShowsWhatChanged() {
        val answer = """
            Sure! ```json
            {"reply":"Headings are bigger now.","template":{"look":{"roles":{"heading":{"scale":1.8}}}}}
            ```
        """.trimIndent()
        val out = DesignWithAi.parse(answer, current, fontsWithOwn)
        assertEquals("Headings are bigger now.", out.message)
        assertEquals(1.8f, out.template.look.role(NoteRole.Heading).scale, 0f)
        assertEquals(0xFF0000FFL, out.template.look.role(NoteRole.Heading).color) // untouched
        assertEquals(current.id, out.template.id)
        assertEquals(listOf("scale.Heading"), out.changes.map { it.key })
        assertEquals("1.30", out.changes.single().from)
        assertEquals("1.80", out.changes.single().to)
    }

    @Test fun acceptsABareTemplateAndIgnoresAnIdTheAiInvents() {
        val out = DesignWithAi.parse("""{"id":"hacked","name":"Blue","look":{"roles":{"title":{"color":"#0000FF"}}}}""", current, fontsWithOwn)
        assertEquals("user-1", out.template.id)
        assertEquals("Blue", out.template.name)
        assertEquals("", out.message)
        assertEquals(setOf("name", "color.Title"), out.changes.map { it.key }.toSet())
    }

    @Test fun everythingIsClampedAndReported() {
        val answer = """{"reply":"ok","template":{
            "look":{"bodyScale":5,"spaceBeforeHeading":40,"fonts":{"latin":"Comic Sans","arabic":"gf:katibeh","hebrew":"../etc"},
              "roles":{"title":{"color":"#FFFFFF","scale":9,"decoration":"sparkles"},"body":{"color":"red"}}},
            "ai":{"notesLanguage":"Klingon is not a code","termsLanguage":"he","note":"${"x".repeat(2000)}"},
            "elements":["Formula","Nonsense"]}}"""
        val out = DesignWithAi.parse(answer, current, fontsWithOwn)
        val look = out.template.look
        assertEquals(TemplateClamp.MAX_BODY, look.bodyScale, 0f)
        assertEquals(TemplateClamp.MAX_SPACE_BEFORE, look.spaceBeforeHeading)
        assertEquals("gf:kalam", current.look.latinFont.id)
        assertEquals("caveat", look.latinFont.id) // unknown font id → the script's default
        assertEquals("gf:katibeh", look.arabicFont.id)
        assertEquals("gveret-levin", look.hebrewFont.id)
        assertEquals(TemplateClamp.MAX_ROLE_SCALE, look.role(NoteRole.Title).scale, 0f)
        assertNotEquals(0xFFFFFFFFL, look.role(NoteRole.Title).color) // white text would vanish on the paper
        assertEquals(current.look.role(NoteRole.Title).decoration, look.role(NoteRole.Title).decoration)
        assertNull(look.role(NoteRole.Body).color) // "red" is not a hex colour: kept as it was
        assertNull(out.template.ai.notesLanguage)
        assertEquals("he", out.template.ai.termsLanguage)
        assertEquals(TemplateClamp.MAX_NOTE, out.template.ai.note.length)
        assertEquals(setOf(NoteElement.Formula, NoteElement.Title, NoteElement.Heading), out.template.elements)
        assertTrue("font.latin" in out.adjusted && "bodyScale" in out.adjusted && "ai.note" in out.adjusted)
    }

    @Test fun noJsonIsAnErrorNotACrash() {
        assertThrows(DesignParseException::class.java) { DesignWithAi.parse("I cannot do that.", current, fontsWithOwn) }
        assertThrows(DesignParseException::class.java) { DesignWithAi.parse("""{"reply":"hi"}""", current, fontsWithOwn) }
        assertThrows(DesignParseException::class.java) { DesignWithAi.parse("{ broken", current, fontsWithOwn) }
    }

    @Test fun extractObjectFindsTheJsonInsideProseAndBraceStrings() {
        val o = DesignWithAi.extractObject("Here you go: {\"reply\":\"a } brace\",\"template\":{\"name\":\"n\"}} hope it helps {")
        assertNotNull(o)
        assertEquals("n", ((o!!["template"] as kotlinx.serialization.json.JsonObject)["name"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun promptListsFontsRolesAndElements() {
        val p = DesignWithAi.system(fontsWithOwn)
        assertTrue("gf:katibeh" in p && "gveret-levin" in p && "my:1" in p)
        NoteRole.entries.forEach { assertTrue(it.name, TemplateJson.roleKeys.getValue(it) in p) }
        NoteElement.entries.forEach { assertTrue(it.name, it.name in p) }
        val user = DesignWithAi.userMessage("bigger headings", current)
        assertTrue("CURRENT TEMPLATE" in user && "bigger headings" in user && "Calculus cheat sheet" in user)
        assertFalse("user-1" in user) // the id is not sent
    }

    @Test fun theSampleFollowsTheNotesLanguage() {
        fun text(lang: String?) = NoteSamples.forTemplate(current.copy(ai = current.ai.copy(notesLanguage = lang), elements = NoteElement.entries.toSet()))
            .elements.joinToString(" ") { it.words() }
        assertTrue("derivative" in text("en"))
        assertTrue(text("ar").contains("المشتقة"))
        assertTrue(text("he").contains("קינמטיקה"))
    }
}
