package app.tuned.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import app.tuned.core.FreezeWatchdog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The freeze catcher (Melvin, 2026-11-19, from the Horizon Maps review). A crash leaves a report ([CrashReporter]); a
 * freeze, where the screen stops answering and Android offers "close app", never reaches the crash handler, so it used
 * to leave nothing behind. Two things catch it now, both saved as reports under Settings › Updates and advanced › Logs:
 *
 * - **While it happens.** A low-priority thread asks the screen thread to answer once a second. When [LIMIT_MS] pass
 *   with no answer, it saves what the screen thread was doing at that moment, once per freeze ([FreezeWatchdog]).
 * - **After Android closed the app for it.** On Android 11 and newer, Android keeps the reasons the app's last
 *   processes ended. Each "not responding" ending not yet seen is saved on the next start, with Android's own trace.
 *
 * Neither runs while a debugger holds the app paused, which would look like a freeze.
 */
object FreezeWatch {
    private const val TAG = "Freeze"
    private const val LIMIT_MS = 5_000L
    private const val TICK_MS = 1_000L
    private const val PREFS = "freeze_watch"
    private const val SEEN = "anr_seen_ms"
    private const val EXITS = 10
    private const val TRACE_CHARS = 20_000
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        // Unit tests' simulated screen thread only answers when a test lets it, which would read as a freeze.
        if (Build.FINGERPRINT == "robolectric" || !started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val dog = FreezeWatchdog(LIMIT_MS, SystemClock.uptimeMillis())
        val waiting = AtomicBoolean(false)
        val answer =
            Runnable {
                dog.answered(SystemClock.uptimeMillis())
                waiting.set(false)
            }
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                // One question at a time: a stuck screen thread doesn't pile up a queue of them.
                if (waiting.compareAndSet(false, true)) main.post(answer)
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (dog.check(SystemClock.uptimeMillis()) && !Debug.isDebuggerConnected()) report(app)
            }
        }, "freeze-watch")
            .apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.start()
    }

    private fun report(context: Context) {
        val stack =
            Looper
                .getMainLooper()
                .thread.stackTrace
                .joinToString("\n") { "  at $it" }
        AppLog.w(TAG, "The screen stopped answering for ${LIMIT_MS / 1_000} seconds")
        CrashReporter.saveFreeze(
            context,
            System.currentTimeMillis(),
            "the screen stopped answering for ${LIMIT_MS / 1_000} seconds or more",
            "Screen thread:\n$stack",
        )
    }

    /** Saves each "not responding" ending Android recorded since the last look. Reads files: call off the main thread. */
    fun collectPastFreezes(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        attempt(TAG, "Couldn't read why Tuned last closed") { collect(context) }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collect(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getLong(SEEN, 0L)
        val activity = context.getSystemService(ActivityManager::class.java) ?: return
        val anrs =
            activity
                .getHistoricalProcessExitReasons(context.packageName, 0, EXITS)
                .filter { it.reason == ApplicationExitInfo.REASON_ANR && it.timestamp > seen }
        // The first start after this feature arrives only remembers where it is, so old freezes aren't reported as new.
        if (seen > 0L) {
            anrs.sortedBy { it.timestamp }.forEach { exit ->
                val trace =
                    attempt(TAG, "Couldn't read a freeze trace") {
                        exit.traceInputStream?.bufferedReader()?.use { it.readText().take(TRACE_CHARS) }
                    }.getOrNull()
                CrashReporter.saveFreeze(
                    context,
                    exit.timestamp,
                    "Android closed it because it wasn't responding (${exit.description.orEmpty()})",
                    trace ?: "Android kept no trace for this one.",
                )
            }
        }
        val newest = anrs.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
        prefs.edit { putLong(SEEN, maxOf(seen, newest)) }
    }
}
