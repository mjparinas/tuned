package com.tunedplayer.app.core

/**
 * How much longer a countable job has left, from how fast [done] of [total] has been moving since [startedAt]
 * (Melvin, 2026-11-22: estimate how much longer it'll take from how fast it's syncing).
 */
object Eta {
    /** Wait this long after the clock starts before guessing, so the first few ticks don't swing wildly. */
    const val READY_AFTER_MS = 3_000L

    /** Hide the guess when less than this remains; "almost done" is clearer than "about 5 sec left". */
    const val HIDE_BELOW_MS = 5_000L

    /**
     * Milliseconds still to go at the current rate, or null when there isn't enough to say: no size yet, nothing done,
     * finished, the clock hasn't started, or it has only just begun.
     */
    fun remainingMs(
        done: Long,
        total: Long,
        startedAt: Long,
        now: Long,
    ): Long? {
        if (total <= 0L || done <= 0L || done >= total || startedAt <= 0L) return null
        val elapsed = now - startedAt
        if (elapsed < READY_AFTER_MS) return null
        val rate = done.toDouble() / elapsed
        if (rate <= 0.0) return null
        val left = ((total - done) / rate).toLong()
        return left.takeIf { it >= HIDE_BELOW_MS }
    }

    /** A rounded unit for the words next to a progress count ("about 3 min left"). */
    sealed class Round {
        data class Seconds(
            val n: Int,
        ) : Round()

        data class Minutes(
            val n: Int,
        ) : Round()

        data class Hours(
            val n: Int,
        ) : Round()
    }

    /** Turns raw remaining milliseconds into a short, stable unit for display. */
    fun round(remainingMs: Long): Round = when {
        remainingMs < 90_000L -> Round.Seconds((((remainingMs + 2_500L) / 5_000L) * 5L).toInt().coerceAtLeast(5))

        remainingMs < 90L * 60_000L -> Round.Minutes(((remainingMs + 30_000L) / 60_000L).toInt().coerceAtLeast(1))

        // Floor to whole hours so 90 minutes reads as "about 1 hr", not "about 2 hr".
        else -> Round.Hours((remainingMs / 3_600_000L).toInt().coerceAtLeast(1))
    }
}
