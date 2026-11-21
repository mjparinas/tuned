package com.tunedplayer.app.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quiet start keeps background work off the first screen (Melvin, 2026-11-20: 2.2.0 took 20 to 30 seconds to reach
 * "Who's watching?" on his TV). Real time with short limits, like [StallWatchdogTest], and wide margins.
 */
class QuietStartTest {
    private fun quiet(scope: CoroutineScope) = QuietStart(scope, afterHomeMs = AFTER_HOME, limitMs = LIMIT, noScreenMs = NO_SCREEN)

    private fun <T> withScope(block: suspend (CoroutineScope) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            runBlocking { block(scope) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun workWaitsWhileTheFirstScreenComesUp() = withScope { scope ->
        val gate = quiet(scope)
        gate.begin()
        gate.screenStarting()
        delay(NO_SCREEN * 3)
        assertFalse("Background work ran before Home was up", gate.isOpen.value)
    }

    @Test
    fun workStartsShortlyAfterHome() = withScope { scope ->
        val gate = quiet(scope)
        gate.begin()
        gate.screenStarting()
        gate.homeShown()
        assertFalse(gate.isOpen.value)
        withTimeout(LIMIT / 2) { gate.await() }
        assertTrue(gate.isOpen.value)
    }

    @Test
    fun someoneSittingOnWhosWatchingStillSyncs() = withScope { scope ->
        val gate = quiet(scope)
        gate.begin()
        gate.screenStarting()
        withTimeout(LIMIT * 3) { gate.await() }
        assertTrue(gate.isOpen.value)
    }

    @Test
    fun backgroundOnlyStartsDontWaitForAScreen() = withScope { scope ->
        val gate = quiet(scope)
        gate.begin()
        withTimeout(LIMIT / 2) { gate.await() }
        assertTrue(gate.isOpen.value)
    }

    @Test
    fun aRefreshThePersonAskedForStartsAtOnce() = withScope { scope ->
        val gate = quiet(scope)
        gate.begin()
        gate.screenStarting()
        gate.openNow()
        withTimeout(NO_SCREEN) { gate.await() }
    }

    private companion object {
        const val NO_SCREEN = 100L
        const val AFTER_HOME = 200L
        const val LIMIT = 4_000L
    }
}
