package com.tunedplayer.app.core

/**
 * What to do with a failure nothing else caught (Melvin, 2026-11-20: "Make it so the app doesn't crash at all. Keep
 * things as stable as possible"). Every one is logged and saved as a report first; this only decides what follows.
 *
 * - A failed coroutine ([fromCoroutine]) has already ended on its own and the thread it ran on carries on, so the app
 *   keeps going: the screen keeps what it last showed instead of closing.
 * - A failure that ends a background thread leaves the rest of the app as it was, so the app keeps going too.
 * - A failure that unwinds the screen thread leaves nothing to draw or answer taps, so the app restarts itself on its
 *   home screen when it was on screen, rather than freezing or showing Android's "keeps stopping" box.
 * - Running out of memory, or a restart within [LOOP_MS] of the last one (a crash on every start), goes to Android as
 *   a crash, so a loop ends instead of repeating.
 */
object CrashPolicy {
    enum class Action { KEEP_GOING, RESTART, HAND_TO_ANDROID }

    /** A second restart this soon after the first means it fails on start: stop restarting. */
    const val LOOP_MS = 60_000L

    fun decide(
        error: Throwable,
        onScreenThread: Boolean,
        fromCoroutine: Boolean,
        onScreen: Boolean,
        lastRestartMs: Long,
        nowMs: Long,
    ): Action {
        val fatal = error is VirtualMachineError
        if (!fatal && (fromCoroutine || !onScreenThread)) return Action.KEEP_GOING
        val looping = lastRestartMs > 0 && nowMs - lastRestartMs in 0 until LOOP_MS
        return if (onScreen && !looping) Action.RESTART else Action.HAND_TO_ANDROID
    }
}
