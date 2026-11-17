package app.tuned.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Logs a coroutine's failure instead of crashing the app; cancellation is never a failure and is never seen here. */
fun loggingHandler(tag: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error ->
    AppLog.e(tag, "A background task failed", error)
}

/**
 * [launch] for work whose failure must not take the app down: a settings write, a database update, a tap's side
 * effect. The failure is logged (Settings › Developer › Logs) and the screen carries on. Work whose result the
 * person waits for should still handle its errors itself and say what went wrong.
 */
fun CoroutineScope.launchSafely(
    tag: String,
    block: suspend CoroutineScope.() -> Unit,
): Job = launch(loggingHandler(tag), block = block)

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
