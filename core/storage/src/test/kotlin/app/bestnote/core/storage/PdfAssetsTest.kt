package app.bestnote.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PdfAssetsTest {
    @Test fun `same bytes give the same valid asset path, different bytes do not`() {
        val a = PdfAssets.pathFor("%PDF-1.4 a".toByteArray())
        assertEquals(a, PdfAssets.pathFor("%PDF-1.4 a".toByteArray()))
        assertNotEquals(a, PdfAssets.pathFor("%PDF-1.4 b".toByteArray()))
        BnoteFormat.requireValidAssetPath(a)
    }
}
