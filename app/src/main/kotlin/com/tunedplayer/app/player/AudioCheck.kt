package com.tunedplayer.app.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/**
 * Silent channels (2026-11-17, from the app audit). Many sports channels carry Dolby audio (AC-3, E-AC-3) or DTS, which
 * plenty of phones can't decode, so the picture plays with no sound. When the stream has another audio track this
 * device can play, it is chosen instead; when it has none, [onNoSound] says why, once per channel, rather than leaving
 * the person to guess. (A software Dolby decoder would need FFmpeg built into the app; see the player decisions.)
 */
internal class AudioCheck(
    private val onNoSound: (codec: String) -> Unit,
) {
    private var warnedFor: String? = null

    fun onTracks(
        player: Player,
        tracks: Tracks,
    ) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty()) return
        val selected = audio.firstOrNull { it.isSelected }
        val selectedPlays =
            selected != null && (0 until selected.length).any { selected.isTrackSelected(it) && selected.isTrackSupported(it) }
        if (selectedPlays) return
        val playable = audio.firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSupported(it) }?.let { g to it } }
        if (playable != null) {
            val (group, index) = playable
            player.trackSelectionParameters =
                player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
            return
        }
        val id = player.currentMediaItem?.mediaId
        if (id == warnedFor) return
        warnedFor = id
        val mime = audio.first().getTrackFormat(0).sampleMimeType
        onNoSound(codecName(mime))
    }

    private fun codecName(mime: String?): String = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital (AC-3)"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus (E-AC-3)"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
        else -> mime?.substringAfter('/')?.uppercase() ?: "?"
    }
}
