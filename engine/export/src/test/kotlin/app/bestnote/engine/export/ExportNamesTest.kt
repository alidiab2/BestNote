package app.bestnote.engine.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamesTest {
    @Test fun `unsafe characters become underscores`() {
        assertEquals("Calc_ I_ week 2", ExportNames.fileBase("Calc/ I: week 2"))
    }

    @Test fun `blank and dot-only titles get a default`() {
        assertEquals("Notebook", ExportNames.fileBase("  "))
        assertEquals("Notebook", ExportNames.fileBase("..."))
    }

    @Test fun `long titles are cut and arabic is kept`() {
        assertEquals(80, ExportNames.fileBase("x".repeat(200)).length)
        assertEquals("ملاحظات الفيزياء", ExportNames.fileBase("ملاحظات الفيزياء"))
    }
}
