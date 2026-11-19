package com.tunedplayer.app.core

import kotlin.math.abs

/**
 * Subtitle timing (Melvin, 2026-11-17): how far the captions move against the picture. Positive shows them later, for
 * captions that come before the voice; negative shows them earlier. Steps add up and stop at [LIMIT_MS] either way.
 */
object SubtitleOffset {
    const val LIMIT_MS = 30_000L
    const val SMALL_STEP_MS = 100L
    const val BIG_STEP_MS = 1_000L

    fun step(
        currentMs: Long,
        byMs: Long,
    ): Long = (currentMs + byMs).coerceIn(-LIMIT_MS, LIMIT_MS)

    /** "1.5" for 1,500 ms: seconds to one decimal place, without the sign (the label says earlier or later). */
    fun seconds(offsetMs: Long): String {
        val tenths = (abs(offsetMs) + 50) / 100
        return "${tenths / 10}.${tenths % 10}"
    }

    /** Where a cue that starts at [startUs] goes. [unset] marks a cue timed by its sample, which the offset then times. */
    fun shift(
        startUs: Long,
        offsetMs: Long,
        unset: Long,
    ): Long = if (startUs == unset) offsetMs * 1_000 else startUs + offsetMs * 1_000
}
