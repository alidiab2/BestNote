package app.bestnote.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimelineTest {
    @Test fun strokeDuringRecordingGetsItsOffset() {
        assertEquals(AudioSync("r", 4_200), AudioTimeline.stamp("r", recordingStartUptime = 10_000, strokeStartUptime = 14_200))
        assertEquals(AudioSync("r", 0), AudioTimeline.stamp("r", 10_000, 10_000))
    }

    @Test fun strokeBeforeRecordingIsNotStamped() {
        assertNull(AudioTimeline.stamp("r", 10_000, 9_999))
    }

    @Test fun seekStartsAShortLeadInBeforeTheStroke() {
        assertEquals(4_000, AudioTimeline.seekTarget(AudioSync("r", 5_000), 60_000))
        assertEquals(0, AudioTimeline.seekTarget(AudioSync("r", 300), 60_000))
        assertEquals(60_000, AudioTimeline.seekTarget(AudioSync("r", 99_000), 60_000)) // clamped to the end
    }

    @Test fun inkLaterThanThePlayheadIsDimmedOnlyForItsOwnRecording() {
        val s = AudioSync("r", 5_000)
        assertTrue(AudioTimeline.isLater(s, "r", 4_999))
        assertFalse(AudioTimeline.isLater(s, "r", 5_000))
        assertFalse(AudioTimeline.isLater(s, "other", 0))
        assertFalse(AudioTimeline.isLater(null, "r", 0)) // ink written without a recording is never dimmed
    }

    @Test fun writtenCountCountsOffsetsAtOrBeforeThePlayhead() {
        val offsets = longArrayOf(100, 200, 200, 900)
        assertEquals(0, AudioTimeline.writtenCount(offsets, 99))
        assertEquals(1, AudioTimeline.writtenCount(offsets, 100))
        assertEquals(3, AudioTimeline.writtenCount(offsets, 200))
        assertEquals(4, AudioTimeline.writtenCount(offsets, 10_000))
        assertEquals(0, AudioTimeline.writtenCount(longArrayOf(), 5))
    }

    @Test fun recordingIdsMustBeSafeFileNames() {
        assertThrows(IllegalArgumentException::class.java) { AudioRecording("../x", null, 0, 0) }
        assertEquals("assets/audio/abc-1.m4a", AudioRecording("abc-1", null, 0, 0).assetPath)
    }
}
