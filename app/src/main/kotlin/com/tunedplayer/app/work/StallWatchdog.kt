package com.tunedplayer.app.work

import com.tunedplayer.app.data.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ends a refresh that has stopped moving, so the first download can never spin forever (Melvin, 2026-11-20: Live TV
 * sat on "Getting your channels ready" on a TV after restoring from his Tuned account). Moving means new bytes or rows,
 * or a new step; a report that repeats the last one (a download waiting on a server that hasn't answered) is not.
 * [armed] is checked each time, so the watch can stand down once there is something to show (the cache has channels).
 */
class StallWatchdog(
    private val limitMs: Long,
    private val now: () -> Long,
    private val checkEveryMs: Long = CHECK_EVERY_MS,
) {
    private var last: Pair<SyncProgress.Step, Long>? = null

    @Volatile private var movedAt = now()

    /** Every progress report, before any throttling. */
    fun saw(progress: SyncProgress) {
        val key = progress.step to progress.done
        if (key != last) {
            last = key
            movedAt = now()
        }
    }

    /** Runs [block]; throws [Stalled] if it goes [limitMs] without moving while [armed] says to watch. */
    suspend fun <T> watch(
        armed: suspend () -> Boolean,
        block: suspend () -> T,
    ): T = coroutineScope {
        movedAt = now()
        val work = async { block() }
        val watcher =
            launch {
                while (true) {
                    delay(checkEveryMs)
                    if (now() - movedAt >= limitMs && armed()) {
                        work.cancel(Stalled(limitMs))
                        break
                    }
                }
            }
        try {
            work.await()
        } finally {
            watcher.cancel()
        }
    }

    /** The refresh made no progress for [limitMs]. A cancellation, so the download hangs up at once. */
    class Stalled(
        val limitMs: Long,
    ) : CancellationException("No progress for ${limitMs / MS_PER_SECOND} seconds")

    companion object {
        /** Five minutes with nothing new: long enough for a slow panel to start answering, short enough to not feel stuck. */
        const val FIRST_DOWNLOAD_LIMIT_MS = 5 * 60 * 1_000L
        private const val CHECK_EVERY_MS = 5_000L
        private const val MS_PER_SECOND = 1_000L
    }
}
