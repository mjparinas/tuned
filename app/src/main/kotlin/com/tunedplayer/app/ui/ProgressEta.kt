package com.tunedplayer.app.ui

import android.content.Context
import com.tunedplayer.app.R
import com.tunedplayer.app.core.Eta

/** Words for how much longer a countable job has left, appended after its count or size. */
object ProgressEta {
    /**
     * "about 3 min left" from [remainingMs], or null when there isn't enough to say ([Eta.remainingMs] returned null).
     */
    fun format(
        context: Context,
        remainingMs: Long?,
    ): String? {
        remainingMs ?: return null
        return when (val round = Eta.round(remainingMs)) {
            is Eta.Round.Seconds -> context.resources.getQuantityString(R.plurals.progress_seconds_left, round.n, round.n)
            is Eta.Round.Minutes -> context.resources.getQuantityString(R.plurals.progress_minutes_left, round.n, round.n)
            is Eta.Round.Hours -> context.resources.getQuantityString(R.plurals.progress_hours_left, round.n, round.n)
        }
    }

    /**
     * [line] with " · about 3 min left" when [done] of [total] since [startedAt] is enough to guess; otherwise [line]
     * alone.
     */
    fun withEta(
        context: Context,
        line: String,
        done: Long,
        total: Long,
        startedAt: Long,
        now: Long = System.currentTimeMillis(),
    ): String {
        val eta = format(context, Eta.remainingMs(done, total, startedAt, now)) ?: return line
        return "$line · $eta"
    }
}
