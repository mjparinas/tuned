package com.tunedplayer.app.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sound delay moves the clock the picture follows, and nothing else (Melvin, 2026-11-19: "do 4"). */
@OptIn(UnstableApi::class)
class AudioDelayTest {
    /** A sink that reports [positionUs] as where its sound is. */
    private fun sinkAt(positionUs: Long): AudioSink =
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentPositionUs" -> positionUs
                else -> null
            }
        } as AudioSink

    @Test
    fun soundLaterRunsTheClockAhead() {
        assertEquals(1_200_000L, DelayedAudioSink(sinkAt(1_000_000L)) { 200 }.getCurrentPositionUs(false))
    }

    @Test
    fun soundEarlierHoldsTheClockBack() {
        assertEquals(850_000L, DelayedAudioSink(sinkAt(1_000_000L)) { -150 }.getCurrentPositionUs(false))
    }

    @Test
    fun noDelayChangesNothing() {
        assertEquals(1_000_000L, DelayedAudioSink(sinkAt(1_000_000L)) { 0 }.getCurrentPositionUs(false))
    }

    @Test
    fun anUnknownPositionStaysUnknown() {
        assertEquals(
            AudioSink.CURRENT_POSITION_NOT_SET,
            DelayedAudioSink(sinkAt(AudioSink.CURRENT_POSITION_NOT_SET)) { 300 }.getCurrentPositionUs(false),
        )
    }
}
