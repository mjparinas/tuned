package com.tunedplayer.app.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Match frame rate (Melvin, 2026-11-17, from the app audit). A TV stuck at 60 Hz shows 50 fps sport and 24 fps films
 * with a judder; switching the display to a matching refresh rate, as Kodi and TiviMate do, makes motion smooth.
 */
object FrameRates {
    /** A display mode with the same resolution as the current one. */
    data class Mode(
        val id: Int,
        val refreshRate: Float,
    )

    /**
     * The mode to switch to for video at [fps], or null to stay. A refresh rate equal to the frame rate wins, then
     * twice it (25 fps on 50 Hz, 29.97 on 59.94); a mode matches within 0.05%, so 23.976 doesn't take 24 Hz.
     * [current] is kept when it already matches. Rates outside 10 to 120 fps are ignored as bad metadata.
     */
    fun pick(
        fps: Float,
        modes: List<Mode>,
        current: Mode?,
    ): Mode? {
        if (fps !in MIN_FPS..MAX_FPS) return null
        if (current != null && multiple(current.refreshRate, fps) in 1..2) return null
        for (times in 1..2) {
            modes.firstOrNull { multiple(it.refreshRate, fps) == times }?.let { return it }
        }
        return null
    }

    /** How many frames of video each refresh shows a whole number of times, or 0 when it doesn't divide evenly. */
    private fun multiple(
        refresh: Float,
        fps: Float,
    ): Int {
        val ratio = refresh / fps
        val whole = ratio.roundToInt()
        return if (whole >= 1 && abs(ratio - whole) / whole < TOLERANCE) whole else 0
    }

    private const val MIN_FPS = 10f
    private const val MAX_FPS = 120f
    private const val TOLERANCE = 0.0005f
}
