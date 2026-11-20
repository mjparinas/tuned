package com.tunedplayer.app.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CrashReporter keeps the app going after a coroutine's failure because that failure arrives marked
 * (Melvin, 2026-11-20: "Make it so the app doesn't crash at all"). The mark comes from a handler kotlinx.coroutines
 * finds through META-INF/services, so this checks the wiring end to end: an unhandled coroutine failure reaches the
 * thread's handler marked, and a plain thread's failure doesn't.
 */
class CoroutineFailureMarkTest {
    @Test
    fun anUnhandledCoroutineFailureArrivesMarked() {
        val caught = catchOn { CoroutineScope(Job() + Dispatchers.Unconfined).launch { error("a bug in a coroutine") } }
        assertTrue(CoroutineFailureMark.isMarked(caught))
    }

    @Test
    fun aPlainThreadFailureArrivesUnmarked() {
        val caught = catchOn { error("a bug on a thread") }
        assertFalse(CoroutineFailureMark.isMarked(caught))
    }

    private fun catchOn(work: () -> Unit): Throwable {
        var caught: Throwable? = null
        val done = CountDownLatch(1)
        val thread = Thread(work)
        thread.setUncaughtExceptionHandler { _, e ->
            caught = e
            done.countDown()
        }
        thread.start()
        done.await(5, TimeUnit.SECONDS)
        return assertNotNull(caught)
    }
}
