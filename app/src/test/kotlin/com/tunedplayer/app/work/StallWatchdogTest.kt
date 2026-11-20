package com.tunedplayer.app.work

import com.tunedplayer.app.data.SyncProgress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real time with short limits rather than runTest: runTest fails on any coroutine exception another test in the same
 * JVM left behind, which has nothing to do with the watchdog. The margins are wide (10x) so a busy machine can't fail it.
 */
class StallWatchdogTest {
    private fun watchdog() = StallWatchdog(limitMs = LIMIT, now = System::currentTimeMillis, checkEveryMs = CHECK)

    @Test
    fun aDownloadThatNeverAnswersIsEnded() = runBlocking {
        val dog = watchdog()
        val started = System.currentTimeMillis()
        var stalled: StallWatchdog.Stalled? = null
        try {
            dog.watch(armed = { true }) {
                // The server never answers: the download only repeats "waiting" (done 0).
                while (true) {
                    dog.saw(SyncProgress(SyncProgress.Step.DownloadPlaylist))
                    delay(CHECK)
                }
            }
        } catch (e: StallWatchdog.Stalled) {
            stalled = e
        }
        val took = System.currentTimeMillis() - started
        assertTrue("a silent first download must end", stalled != null)
        assertTrue("ended after ${took}ms", took >= LIMIT)
    }

    @Test
    fun aSlowDownloadThatKeepsMovingIsLeftAlone() = runBlocking {
        val dog = watchdog()
        val result =
            dog.watch(armed = { true }) {
                for (bytes in 1L..30L) {
                    delay(LIMIT / 10)
                    dog.saw(SyncProgress(SyncProgress.Step.DownloadPlaylist, bytes * 1_000))
                }
                "done"
            }
        assertEquals("done", result)
    }

    @Test
    fun onceChannelsAreCachedNothingIsEnded() = runBlocking {
        val dog = watchdog()
        val result =
            dog.watch(armed = { false }) {
                delay(LIMIT * 3)
                "guide done"
            }
        assertEquals("guide done", result)
    }

    @Test
    fun aRealCancellationStillGoesThrough() = runBlocking {
        val dog = watchdog()
        val job =
            launch {
                dog.watch(armed = { false }) { awaitCancellation() }
            }
        delay(CHECK)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    private companion object {
        const val LIMIT = 500L
        const val CHECK = 10L
    }
}
