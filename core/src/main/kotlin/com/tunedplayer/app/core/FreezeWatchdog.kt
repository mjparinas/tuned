package com.tunedplayer.app.core

/**
 * When the screen thread has stopped answering (Melvin, 2026-11-19, from the Horizon Maps review: "freeze catcher").
 * A watchdog thread asks the screen thread to answer, notes each answer with [answered], and asks [check] on every tick.
 * A freeze is reported once, when [limitMs] has passed with no answer, and again only after the screen thread has
 * answered and frozen again, so a long hang is one report, not one a second.
 */
class FreezeWatchdog(
    private val limitMs: Long,
    startMs: Long,
) {
    private var lastAnswerMs = startMs
    private var reported = false

    /** The screen thread answered at [nowMs]. */
    @Synchronized
    fun answered(nowMs: Long) {
        lastAnswerMs = nowMs
        reported = false
    }

    /** True exactly once per freeze: the first tick at which [limitMs] has passed since the last answer. */
    @Synchronized
    fun check(nowMs: Long): Boolean {
        if (reported || nowMs - lastAnswerMs < limitMs) return false
        reported = true
        return true
    }

    /** How long the screen thread has gone without answering, as of [nowMs]. */
    @Synchronized
    fun stuckFor(nowMs: Long): Long = (nowMs - lastAnswerMs).coerceAtLeast(0)
}
