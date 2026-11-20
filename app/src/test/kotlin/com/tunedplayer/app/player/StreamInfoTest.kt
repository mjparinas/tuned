package com.tunedplayer.app.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamInfoTest {
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

    @Test
    fun pillsRoundTheFrameRateAndLeaveOutWhatIsUnknown() {
        val p = StreamInfo.pills(1920, 1080, 23.976f, "audio/mp4a-latm", 2)
        assertEquals("1080p", p.resolution)
        assertEquals(24, p.frameRate)
        assertEquals("AAC", p.codec)
        assertEquals(StreamInfo.Speakers.STEREO, p.speakers)
        val none = StreamInfo.pills(0, 0, -1f, null, -1)
        assertNull(none.resolution)
        assertNull(none.frameRate)
        assertNull(none.codec)
        assertNull(none.speakers)
        assertEquals(60, StreamInfo.pills(1280, 720, 59.94f, null, 0).frameRate)
    }

    @Test
    fun resolutionsUseFriendlyNamesAndFallBackToTheSize() {
        assertEquals("4K", StreamInfo.resolutionName(3840, 2160))
        assertEquals("4K", StreamInfo.resolutionName(4096, 2160))
        assertEquals("720p", StreamInfo.resolutionName(1280, 720))
        assertEquals("1080p", StreamInfo.resolutionName(1920, 800))
        assertEquals("1080p", StreamInfo.resolutionName(1440, 1080))
        assertEquals("576p", StreamInfo.resolutionName(704, 576))
        assertEquals("480p", StreamInfo.resolutionName(720, 480))
        assertEquals("1000x562", StreamInfo.resolutionName(1000, 562))
        assertNull(StreamInfo.resolutionName(0, 1080))
    }

    @Test
    fun speakersNameTheCommonLayouts() {
        assertEquals(StreamInfo.Speakers.MONO, StreamInfo.speakers(1))
        assertEquals(StreamInfo.Speakers.SURROUND_5_1, StreamInfo.speakers(6))
        assertEquals(StreamInfo.Speakers.SURROUND_7_1, StreamInfo.speakers(8))
        assertEquals(StreamInfo.Speakers.OTHER, StreamInfo.speakers(4))
        assertNull(StreamInfo.speakers(0))
    }
}
