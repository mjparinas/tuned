package com.tunedplayer.app.core

import com.tunedplayer.app.core.CrashPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class CrashPolicyTest {
    private val bug = IllegalStateException("bug")

    private fun decide(
        error: Throwable = bug,
        onScreenThread: Boolean = true,
        fromCoroutine: Boolean = false,
        onScreen: Boolean = true,
        lastRestartMs: Long = 0,
        nowMs: Long = 1_000_000,
    ) = CrashPolicy.decide(error, onScreenThread, fromCoroutine, onScreen, lastRestartMs, nowMs)

    @Test
    fun aFailedCoroutineOnTheScreenThreadKeepsGoing() {
        assertEquals(Action.KEEP_GOING, decide(fromCoroutine = true))
    }

    @Test
    fun aBackgroundThreadFailureKeepsGoing() {
        assertEquals(Action.KEEP_GOING, decide(onScreenThread = false))
        assertEquals(Action.KEEP_GOING, decide(onScreenThread = false, onScreen = false))
    }

    @Test
    fun theScreenThreadUnwindingRestartsWhileOnScreen() {
        assertEquals(Action.RESTART, decide())
    }

    @Test
    fun theScreenThreadUnwindingInTheBackgroundGoesToAndroid() {
        assertEquals(Action.HAND_TO_ANDROID, decide(onScreen = false))
    }

    @Test
    fun aSecondRestartWithinAMinuteGoesToAndroid() {
        assertEquals(Action.HAND_TO_ANDROID, decide(lastRestartMs = 1_000_000 - 30_000))
        assertEquals(Action.RESTART, decide(lastRestartMs = 1_000_000 - CrashPolicy.LOOP_MS))
    }

    @Test
    fun aClockSetBackwardsDoesNotCountAsALoop() {
        assertEquals(Action.RESTART, decide(lastRestartMs = 2_000_000))
    }

    @Test
    fun runningOutOfMemoryNeverKeepsGoing() {
        val oom = OutOfMemoryError()
        assertEquals(Action.RESTART, decide(error = oom, fromCoroutine = true))
        assertEquals(Action.RESTART, decide(error = oom, onScreenThread = false))
        assertEquals(Action.HAND_TO_ANDROID, decide(error = oom, onScreenThread = false, onScreen = false))
    }
}
