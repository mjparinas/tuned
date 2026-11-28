package com.tunedplayer.app.work

import com.tunedplayer.app.diagnostics.SpeedTrace
import com.tunedplayer.app.diagnostics.SpeedTraceReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps background work off the first screen (Melvin, 2026-11-20: 2.2.0 took 20 to 30 seconds to reach "Who's watching?"
 * on his TV; docs/decisions/speed.md › Quiet start). The backup, the channel list for the website, playlist and guide
 * refreshes, downloads, channel scans, the TV home screen's row and the region bookkeeping all wait on [await], so a
 * cold start spends the device on the screen the person is waiting for. The work then runs as it always did.
 *
 * It opens at the first of:
 * - [AFTER_HOME_MS] after Home first draws (the person is past "Who's watching?", or there wasn't one),
 * - [LIMIT_MS] after the app started, so someone sitting on "Who's watching?" or the welcome screen still syncs,
 * - [NO_SCREEN_MS] after the app started with no screen coming up (Android started it only for background work).
 */
class QuietStart(
    private val scope: CoroutineScope,
    private val afterHomeMs: Long = AFTER_HOME_MS,
    private val limitMs: Long = LIMIT_MS,
    private val noScreenMs: Long = NO_SCREEN_MS,
) {
    private val open = MutableStateFlow(false)
    private val screen = MutableStateFlow(false)
    private var homeTimer: Job? = null

    /** True once background work may run. */
    val isOpen: StateFlow<Boolean> = open.asStateFlow()

    /** Starts the clocks; call once, as the app starts. */
    fun begin() {
        scope.launch {
            delay(noScreenMs)
            if (!screen.value) open.value = true
        }
        scope.launch {
            delay(limitMs)
            open.value = true
        }
    }

    /** A screen is coming up (the main screen's onCreate), so the work waits for it. */
    fun screenStarting() {
        SpeedTrace.mark("First screen starting")
        screen.value = true
    }

    /** Home has drawn its first frame: the work can start a moment later. */
    @Synchronized
    fun homeShown() {
        SpeedTrace.mark(SpeedTraceReport.HOME)
        screen.value = true
        if (open.value || homeTimer != null) return
        homeTimer =
            scope.launch {
                delay(afterHomeMs)
                open.value = true
            }
    }

    /** The person asked for something (a refresh, a scan, a download): it starts now rather than after the quiet. */
    fun openNow() {
        open.value = true
    }

    /** Waits until background work may run; returns at once after that. */
    suspend fun await() {
        open.first { it }
    }

    companion object {
        const val AFTER_HOME_MS = 3_000L
        const val LIMIT_MS = 20_000L
        const val NO_SCREEN_MS = 2_000L
    }
}
