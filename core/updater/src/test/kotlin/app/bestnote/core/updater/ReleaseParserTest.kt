package app.bestnote.core.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseParserTest {
    private val json = javaClass.getResource("/releases.json")!!.readText()

    @Test fun `skips drafts, releases without apk and without a version code`() {
        val codes = ReleaseParser.parse(json).map { it.versionCode }
        assertEquals(listOf(150L, 140L, 155L), codes)
    }

    @Test fun `newest is the highest versionCode, prereleases included`() {
        val newest = ReleaseParser.newest(ReleaseParser.parse(json))!!
        assertEquals(155L, newest.versionCode)
        assertEquals("build-7", newest.tag)
    }

    @Test fun `body line wins and is removed from the notes`() {
        val r = ReleaseParser.parse(json).first { it.versionCode == 150L }
        assertTrue(r.prerelease)
        assertEquals("0.1.150-abc1234", r.versionName)
        assertEquals(41234567L, r.apkSize)
        assertEquals("## What's new\n- Faster **ink**", r.notes)
    }

    @Test fun `tag is the fallback and a blank title falls back to the tag`() {
        val r = ReleaseParser.parse(json).first { it.versionCode == 140L }
        assertEquals("v0.1.140-def5678", r.title)
        assertEquals("", r.notes)
    }

    @Test fun `version code variants`() {
        assertEquals(12L, ReleaseParser.versionCodeOf("x", "versionCode: 12"))
        assertEquals(12L, ReleaseParser.versionCodeOf("x", "intro\n**versionCode:** 12"))
        assertEquals(12L, ReleaseParser.versionCodeOf("x", "VERSION_CODE=12"))
        assertEquals(99L, ReleaseParser.versionCodeOf("v0.1.99", ""))
        assertEquals(99L, ReleaseParser.versionCodeOf("0.1.99-abc", null))
        assertNull(ReleaseParser.versionCodeOf("nightly", "no number"))
    }

    @Test fun `garbage and empty input give no releases`() {
        assertTrue(ReleaseParser.parse("not json").isEmpty())
        assertTrue(ReleaseParser.parse("{\"message\":\"rate limited\"}").isEmpty())
        assertTrue(ReleaseParser.parse("[]").isEmpty())
        assertNull(ReleaseParser.newest(emptyList()))
    }
}
