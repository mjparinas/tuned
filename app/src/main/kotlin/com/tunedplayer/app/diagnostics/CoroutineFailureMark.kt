package com.tunedplayer.app.diagnostics

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Tags a coroutine's failure that no handler caught before kotlinx.coroutines hands it to the thread's crash handler
 * (it finds this class through `META-INF/services`). [CrashReporter] then knows the failure came from a coroutine that
 * has already ended, with the thread it ran on still working, so it can log it and keep the app going. Only marks:
 * logging happens once, in [CrashReporter].
 */
class CoroutineFailureMark :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler {
    override fun handleException(
        context: CoroutineContext,
        exception: Throwable,
    ) {
        if (!isMarked(exception)) exception.addSuppressed(Marker())
    }

    /** Carries no stack: it is only a label on the real failure. */
    class Marker : RuntimeException("Failed in a coroutine") {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        fun isMarked(error: Throwable): Boolean = error.suppressed.any { it is Marker }
    }
}
