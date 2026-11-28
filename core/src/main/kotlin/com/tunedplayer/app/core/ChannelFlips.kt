package com.tunedplayer.app.core

/**
 * Quick channel flips (Melvin, 2026-11-28: "faster channel zapping"). A flip after a pause changes the stream at once.
 * Flips that follow each other within [burstMs] only move the channel card on, and the stream changes once, to where
 * the flipping stopped, [settleMs] after the last one. Pressing up five times quickly opens one more connection
 * rather than five, and reaches the fifth channel sooner, since the four between never start. Plain Kotlin: the
 * player keeps the clock and the timer ([com.tunedplayer.app.player.ChannelZap]).
 */
class ChannelFlips(
    private val burstMs: Long = BURST_MS,
    val settleMs: Long = SETTLE_MS,
) {
    private var lastAt: Long? = null

    /** Where quick flips have got to, not yet tuned; null when nothing is waiting. */
    var waiting: Int? = null
        private set

    /** The channel the next flip counts from: where the flipping has got to, or [playing]. */
    fun from(playing: Int): Int = waiting ?: playing

    /** A flip to [to] at [now]: true to change the stream now, false to wait for [settled]. */
    fun flip(
        to: Int,
        now: Long,
    ): Boolean {
        val last = lastAt
        lastAt = now
        val quick = waiting != null || (last != null && now - last < burstMs)
        waiting = if (quick) to else null
        return !quick
    }

    /** The flipping stopped: the channel to tune to, or null when the stream already has it. */
    fun settled(): Int? = waiting.also { waiting = null }

    /** The channel changed some other way (a number typed, a backup copy): nothing is waiting any more. */
    fun reset() {
        waiting = null
    }

    companion object {
        const val BURST_MS = 600L
        const val SETTLE_MS = 300L
    }
}
