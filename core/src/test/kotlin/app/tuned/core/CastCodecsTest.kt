package app.tuned.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastCodecsTest {
    @Test
    fun h264AndHevcAreStreamedPieceByPiece() {
        assertTrue(CastCodecs.streams("video/avc"))
        assertTrue(CastCodecs.streams("video/hevc"))
        // Video an MP4 can't hold goes as it is: only the picture would need re-encoding.
        assertFalse(CastCodecs.streams("video/x-vnd.on2.vp9"))
        assertFalse(CastCodecs.streams("video/mpeg2"))
        assertFalse(CastCodecs.streams(null))
    }

    @Test
    fun onlyStereoOrMonoAacGoesAsItIs() {
        assertTrue(CastCodecs.copiesSound("audio/mp4a-latm", 2))
        assertTrue(CastCodecs.copiesSound("audio/mp4a-latm", 1))
        // The Shield's case: every film and episode failed with 102 while stereo live channels played.
        assertFalse(CastCodecs.copiesSound("audio/mp4a-latm", 6))
        // The Dolby and DTS sound of most TV rips, which a Chromecast can't play.
        assertFalse(CastCodecs.copiesSound("audio/ac3", 6))
        assertFalse(CastCodecs.copiesSound("audio/eac3", 2))
        assertFalse(CastCodecs.copiesSound("audio/vnd.dts", 6))
        assertFalse(CastCodecs.copiesSound("audio/mp4a-latm", null))
        assertEquals(2, CastCodecs.channelsForTv(6))
        assertEquals(1, CastCodecs.channelsForTv(1))
    }

    @Test
    fun theSoundSetupMatchesWhatTheEncoderMakes() {
        assertContentEquals(byteArrayOf(0x11, 0x90.toByte()), CastCodecs.aacConfig(48_000, 2))
        assertContentEquals(byteArrayOf(0x12, 0x10), CastCodecs.aacConfig(44_100, 2))
        // An unusual rate is written out in full.
        assertEquals(5, CastCodecs.aacConfig(47_000, 2).size)
    }

    @Test
    fun receiverErrorsAreNamed() {
        assertEquals("the TV couldn't decode the picture or sound", CastCodecs.describeError(102))
        assertEquals("code 999", CastCodecs.describeError(999))
    }

    @Test
    fun fivePointOneIsMixedForTwoSpeakers() {
        // One frame: front left, front right, centre, low frequency, back left, back right.
        val mixed = CastCodecs.downmix(shortArrayOf(1000, 2000, 1000, 30000, 1000, 0), channels = 6)
        assertEquals(2, mixed.size)
        assertEquals(2414, mixed[0].toInt()) // 1000 + 707 centre + 707 back left; the low frequency is left out
        assertEquals(2707, mixed[1].toInt()) // 2000 + 707 centre
        // Loud peaks are clamped rather than wrapping round.
        val loud = CastCodecs.downmix(ShortArray(6) { Short.MAX_VALUE }, channels = 6)
        assertEquals(Short.MAX_VALUE, loud[0])
        // Stereo goes through untouched.
        val stereo = shortArrayOf(1, 2, 3, 4)
        assertTrue(CastCodecs.downmix(stereo, channels = 2) === stereo)
    }
}
