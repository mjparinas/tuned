package app.tuned.core

import app.tuned.core.CastCodecs.Plan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastCodecsTest {
    @Test
    fun anMkvWithDolbySoundBecomesAnMp4WithAac() {
        // The Shield's case: H.264 and AC-3 in MKV stopped at once with a decode error.
        assertEquals(Plan.REMUX_NEW_AUDIO, CastCodecs.plan("4148599558004010732.mkv", "video/avc", "audio/ac3"))
        assertEquals(Plan.REMUX_NEW_AUDIO, CastCodecs.plan("1.mkv", "video/hevc", "audio/eac3"))
        assertEquals(Plan.REMUX_NEW_AUDIO, CastCodecs.plan("1.mp4", "video/avc", "audio/vnd.dts"))
    }

    @Test
    fun anMkvWithAacIsOnlyRepackaged() {
        assertEquals(Plan.REMUX, CastCodecs.plan("1.mkv", "video/avc", "audio/mp4a-latm"))
        assertEquals(Plan.REMUX, CastCodecs.plan("1.avi", "video/avc", null))
    }

    @Test
    fun aPlayableMp4GoesAsItIs() {
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.mp4", "video/avc", "audio/mp4a-latm"))
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.M4V", "video/hevc", "audio/mpeg"))
    }

    @Test
    fun aCopyIsReusedUntilTheSavedFileChanges() {
        assertTrue(CastCodecs.reuseCopy(copyModified = 2_000, sourceModified = 1_000))
        assertTrue(CastCodecs.reuseCopy(copyModified = 1_000, sourceModified = 1_000))
        assertFalse(CastCodecs.reuseCopy(copyModified = 500, sourceModified = 1_000))
        assertFalse(CastCodecs.reuseCopy(copyModified = null, sourceModified = 1_000))
    }

    @Test
    fun receiverErrorsAreNamed() {
        assertEquals("the TV couldn't decode the picture or sound", CastCodecs.describeError(102))
        assertEquals("code 999", CastCodecs.describeError(999))
    }

    @Test
    fun videoAnMp4CantHoldGoesAsItIs() {
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.mkv", "video/x-vnd.on2.vp9", "audio/opus"))
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.ts", "video/mpeg2", "audio/ac3"))
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.mkv", null, "audio/ac3"))
    }

    @Test
    fun surroundSoundBecomesStereoEvenWhenItIsAac() {
        // The Shield's case: every film and episode failed with 102 while stereo live channels played.
        assertEquals(Plan.REMUX_NEW_AUDIO, CastCodecs.plan("1.mp4", "video/avc", "audio/mp4a-latm", channels = 6))
        assertEquals(Plan.REMUX_NEW_AUDIO, CastCodecs.plan("1.mkv", "video/hevc", "audio/mp4a-latm", channels = 6))
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.mp4", "video/avc", "audio/mp4a-latm", channels = 2))
        assertEquals(Plan.REMUX, CastCodecs.plan("1.mkv", "video/avc", "audio/mp4a-latm", channels = 1))
        assertEquals(2, CastCodecs.channelsForTv(6))
        assertEquals(1, CastCodecs.channelsForTv(1))
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
