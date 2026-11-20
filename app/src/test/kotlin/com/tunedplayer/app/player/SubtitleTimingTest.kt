package com.tunedplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Subtitle timing is usable only while a subtitle track is on (Melvin, 2026-11-20). */
class SubtitleTimingTest {
    @Test
    fun offWithNoSubtitleTracks() {
        assertFalse(SubtitleTiming.showing(Tracks.EMPTY))
        assertFalse(SubtitleTiming.showing(Tracks(listOf(group(MimeTypes.VIDEO_H264, selected = true)))))
    }

    @Test
    fun offWhenSubtitlesExistButAreNotPicked() {
        assertFalse(SubtitleTiming.showing(Tracks(listOf(group(MimeTypes.APPLICATION_SUBRIP, selected = false)))))
    }

    @Test
    fun onWhenASubtitleTrackIsPicked() {
        assertTrue(SubtitleTiming.showing(Tracks(listOf(group(MimeTypes.APPLICATION_SUBRIP, selected = true)))))
    }

    private fun group(
        mime: String,
        selected: Boolean,
    ): Tracks.Group {
        val format = Format.Builder().setSampleMimeType(mime).build()
        return Tracks.Group(TrackGroup(format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
    }
}
