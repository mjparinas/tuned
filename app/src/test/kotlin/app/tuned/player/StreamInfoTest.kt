package app.tuned.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamInfoTest {
    @Test
    fun videoLeavesOutWhatTheStreamDoesNotSay() {
        assertEquals("1920×1080 · 50 fps · H.264 · 6.2 Mbps", StreamInfo.video(1920, 1080, 50f, "video/avc", 6_200_000))
        assertEquals("1280×720 · 29.9 fps · H.265", StreamInfo.video(1280, 720, 29.94f, "video/hevc", -1))
        assertEquals("", StreamInfo.video(0, 0, -1f, null, -1))
    }

    @Test
    fun audioNamesTheCodecAndSkipsAnUndeterminedLanguage() {
        assertEquals("AAC · 2 channels · 48 kHz · en", StreamInfo.audio("audio/mp4a-latm", "2 channels", 48_000, "en"))
        assertEquals("E-AC-3 · 44.1 kHz", StreamInfo.audio("audio/eac3", null, 44_100, "und"))
    }

    @Test
    fun unknownCodecsAreShownAsGiven() {
        assertEquals("x-weird", StreamInfo.codec("video/x-weird"))
        assertNull(StreamInfo.codec(null))
    }

    @Test
    fun qualityChoiceNeedsTwoDifferentRenditions() {
        assertFalse(StreamInfo.hasQualityChoice(emptyList()))
        assertFalse(StreamInfo.hasQualityChoice(listOf(1080 to 6_200_000)))
        assertFalse(StreamInfo.hasQualityChoice(listOf(1080 to 6_200_000, 1080 to 6_200_000)))
        assertTrue(StreamInfo.hasQualityChoice(listOf(1080 to 6_200_000, 720 to 3_000_000)))
    }

    @Test
    fun qualityLabels() {
        assertEquals("1080p · 6.2 Mbps", StreamInfo.quality(1080, 6_200_000))
        assertEquals("720p", StreamInfo.quality(720, 0))
        assertEquals("800 kbps", StreamInfo.quality(0, 800_000))
    }
}
