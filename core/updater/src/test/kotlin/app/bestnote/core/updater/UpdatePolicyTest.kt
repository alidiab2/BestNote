package app.bestnote.core.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private fun release(code: Long) = ReleaseInfo("v0.1.$code", "t", "", false, code, "0.1.$code", "https://x/a.apk", 10)
    private val day = UpdatePolicy.INTERVAL_MILLIS

    @Test fun `only a higher versionCode is newer`() {
        assertTrue(UpdatePolicy.isNewer(release(11), 10))
        assertFalse(UpdatePolicy.isNewer(release(10), 10))
        assertFalse(UpdatePolicy.isNewer(release(9), 10))
    }

    @Test fun `auto check throttled to once per 24 hours and respects the toggle`() {
        val s = UpdateState(lastCheckMillis = 1_000)
        assertFalse(UpdatePolicy.shouldAutoCheck(s, 1_000 + day - 1))
        assertTrue(UpdatePolicy.shouldAutoCheck(s, 1_000 + day))
        assertTrue(UpdatePolicy.shouldAutoCheck(UpdateState(), 5))
        assertFalse(UpdatePolicy.shouldAutoCheck(s.copy(autoCheck = false), 1_000 + 10 * day))
        // Clock moved back: check rather than wait forever.
        assertTrue(UpdatePolicy.shouldAutoCheck(s, 10))
    }

    @Test fun `skip hides that version and older, not newer ones`() {
        val s = UpdatePolicy.skip(UpdateState(), release(20))
        assertFalse(UpdatePolicy.shouldPrompt(release(20), 10, s, 0, manual = false))
        assertFalse(UpdatePolicy.shouldPrompt(release(15), 10, s, 0, manual = false))
        assertTrue(UpdatePolicy.shouldPrompt(release(21), 10, s, 0, manual = false))
    }

    @Test fun `skipping an older version never lowers the skipped code`() {
        val s = UpdatePolicy.skip(UpdatePolicy.skip(UpdateState(), release(20)), release(12))
        assertEquals(20L, s.skippedCode)
    }

    @Test fun `later re-asks after a day`() {
        val s = UpdatePolicy.later(UpdateState(), now = 500)
        assertFalse(UpdatePolicy.shouldPrompt(release(20), 10, s, 500 + day - 1, manual = false))
        assertTrue(UpdatePolicy.shouldPrompt(release(20), 10, s, 500 + day, manual = false))
    }

    @Test fun `a manual check ignores skip and later but not versions`() {
        val s = UpdatePolicy.later(UpdatePolicy.skip(UpdateState(), release(20)), 0)
        assertTrue(UpdatePolicy.shouldPrompt(release(20), 10, s, 1, manual = true))
        assertFalse(UpdatePolicy.shouldPrompt(release(10), 10, s, 1, manual = true))
    }

    @Test fun `size check`() {
        assertTrue(SizeCheck.matches(100, 100))
        assertFalse(SizeCheck.matches(100, 99))
        assertFalse(SizeCheck.matches(100, 0))
        assertTrue(SizeCheck.matches(0, 5))
        assertFalse(SizeCheck.matches(0, 0))
    }
}
