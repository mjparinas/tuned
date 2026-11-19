package com.tunedplayer.app.diagnostics

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Logs a coroutine's failure instead of crashing the app, then runs [onError] (to close a screen that can't go on,
 * say). Cancellation is never a failure and never reaches it.
 */
fun loggingHandler(
    tag: String,
    onError: (Throwable) -> Unit = {},
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    AppLog.e(tag, "A background task failed", error)
    onError(error)
}

/**
 * [launch] for work whose failure must not take the app down: a settings write, a database update, a tap's side
 * effect. The failure is logged (Settings › Updates and advanced › Logs) and the screen carries on. Work whose result the
 * person waits for should still handle its errors itself and say what went wrong.
 */
fun CoroutineScope.launchSafely(
    tag: String,
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(loggingHandler(tag, onError), block = block)

/**
 * [runCatching] that logs the failure and never swallows cancellation (plain `runCatching` in a coroutine would
 * turn a cancelled job into a failure and keep it running).
 */
@Suppress("TooGenericExceptionCaught") // the point: any failure becomes a logged Result, not a crash
inline fun <T> attempt(
    tag: String,
    what: String,
    block: () -> T,
): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.w(tag, what, e)
    Result.failure(e)
}

/**
 * Starts [intent], logging instead of crashing when nothing on the device can handle it or Android won't allow it
 * (an Android TV without a browser, a settings screen a maker removed). Returns whether it started.
 */
fun Context.startActivitySafely(
    intent: Intent,
    tag: String,
): Boolean = try {
    startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    AppLog.w(tag, "Nothing can open ${intent.action}", e)
    false
} catch (e: SecurityException) {
    AppLog.w(tag, "Not allowed to open ${intent.action}", e)
    false
}

/**
 * A background job's body: an unexpected failure is logged and ends this run as failed, instead of reaching
 * WorkManager unrecorded. Expected failures (the network) are still handled inside, with a retry where it helps.
 */
@Suppress("TooGenericExceptionCaught") // logged, and the run is reported failed: nothing is hidden
inline fun <T> logFailures(
    tag: String,
    failed: () -> T,
    block: () -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.e(tag, "Background job failed", e)
    failed()
}
