package app.tuned.core

import app.tuned.core.CastCodecs.Plan
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun videoAnMp4CantHoldGoesAsItIs() {
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.mkv", "video/x-vnd.on2.vp9", "audio/opus"))
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.ts", "video/mpeg2", "audio/ac3"))
        assertEquals(Plan.AS_IS, CastCodecs.plan("1.mkv", null, "audio/ac3"))
    }
}
