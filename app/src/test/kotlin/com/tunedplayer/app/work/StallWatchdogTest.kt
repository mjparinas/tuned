package com.tunedplayer.app.work

import com.tunedplayer.app.data.SyncProgress
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StallWatchdogTest {
    private fun TestScope.watchdog() = StallWatchdog(limitMs = LIMIT, now = { currentTime }, checkEveryMs = 1_000)

    @Test
    fun aDownloadThatNeverAnswersIsEnded() = runTest {
        val dog = watchdog()
        var stalled: StallWatchdog.Stalled? = null
        try {
            dog.watch(armed = { true }) {
                // The server never answers: the download only repeats "waiting" (done 0).
                while (true) {
                    dog.saw(SyncProgress(SyncProgress.Step.Playlist))
                    delay(10_000)
                }
            }
        } catch (e: StallWatchdog.Stalled) {
            stalled = e
        }
        assertTrue("a silent first download must end", stalled != null)
        assertTrue(currentTime in LIMIT..LIMIT + 2_000)
    }

    @Test
    fun aSlowDownloadThatKeepsMovingIsLeftAlone() = runTest {
        val dog = watchdog()
        val result =
            dog.watch(armed = { true }) {
                for (bytes in 1L..20L) {
                    delay(LIMIT / 2)
                    dog.saw(SyncProgress(SyncProgress.Step.Playlist, bytes * 1_000))
                }
                "done"
            }
        assertEquals("done", result)
    }

    @Test
    fun onceChannelsAreCachedNothingIsEnded() = runTest {
        val dog = watchdog()
        val result =
            dog.watch(armed = { false }) {
                delay(LIMIT * 3)
                "guide done"
            }
        assertEquals("guide done", result)
    }

    @Test
    fun aRealCancellationStillGoesThrough() = runTest {
        val dog = watchdog()
        val job =
            launch {
                dog.watch(armed = { true }) { awaitCancellation() }
            }
        delay(1_000)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }

    private companion object {
        const val LIMIT = 300_000L
    }
}
