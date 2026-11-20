package com.tunedplayer.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Lip sync (Melvin, 2026-11-19: "do 4"). A soundbar, a TV's own processing or a Bluetooth speaker can play the sound
 * a little after the picture. The picture follows the sound's clock, so moving that clock moves the picture against
 * the sound: a clock running [delayMs] ahead shows each picture that much before its sound, which plays the sound
 * later, and a negative delay holds the picture back, which plays the sound earlier. Nothing is re-timed or dropped
 * from the sound itself.
 */
@OptIn(UnstableApi::class)
internal class DelayedAudioSink(
    sink: AudioSink,
    private val delayMs: () -> Int,
) : ForwardingAudioSink(sink) {
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = shifted(super.getCurrentPositionUs(sourceEnded), delayMs())

    companion object {
        /** The sound's clock moved by [delayMs]; an unknown position stays unknown. */
        fun shifted(
            positionUs: Long,
            delayMs: Int,
        ): Long = if (positionUs == AudioSink.CURRENT_POSITION_NOT_SET) positionUs else positionUs + delayMs * MICROS_PER_MS

        private const val MICROS_PER_MS = 1_000L
    }
}

/** Media3's renderers with the sound delay from Settings › Player, and decoder fallback on. */
@OptIn(UnstableApi::class)
internal class TunedRenderersFactory(
    context: Context,
    private val delayMs: () -> Int,
) : DefaultRenderersFactory(context) {
    init {
        // If a device's first decoder for a format fails to start, try its next one instead of giving up.
        setEnableDecoderFallback(true)
        // Dolby and DTS sound the device can't decode itself goes to FFmpeg's decoder, after the device's own, when the
        // build has it (CI builds it in; docs/decisions/player.md). A device that can play it, or pass it to a
        // soundbar, still does.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? = super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)?.let { DelayedAudioSink(it, delayMs) }
}
