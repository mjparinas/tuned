package com.tunedplayer.app.diagnostics

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.core.CrashPolicy
import com.tunedplayer.app.ui.MainActivity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

data class CrashReport(
    val timeMs: Long,
    val text: String,
)

/**
 * The last resort for any failure the app didn't handle (Melvin, 2026-11-20: "Make it so the app doesn't crash at
 * all"). Each one is logged and saved as a report, with the log lines that led up to it, to read and share later from
 * Settings › Updates and advanced › Logs and Report a problem. Then [CrashPolicy] decides: a failed coroutine or a
 * background thread is left behind and the app keeps going; a failure that breaks the screen thread restarts the app
 * on its home screen; a crash loop or running out of memory goes to Android as a crash. Freezes are saved here too
 * ([FreezeWatch]). Reports stay on the device, at most [KEEP] of them, and carry no login ([AppLog.scrub]).
 */
object CrashReporter {
    private const val DIR = "crashes"
    private const val KEEP = 10
    private const val RECENT_LINES = 200
    private const val TAG = "Crash"
    private val TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    private const val RESTART_MARK = ".restarted"
    private const val RESTART_EXIT = 10

    /** Recovered failures saved at most this often, so a failure that repeats can't fill the folder or the disk. */
    private const val RECOVERED_GAP_MS = 10_000L

    @Volatile private var lastRecoveredMs = 0L

    fun install(app: Application) {
        val dir = File(app.filesDir, DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            handle(app, dir, thread, error) { previous?.uncaughtException(thread, error) ?: exitProcess(2) }
        }
    }

    @Suppress("TooGenericExceptionCaught") // the handler itself failing must still end in Android's own handling
    private fun handle(
        app: Application,
        dir: File,
        thread: Thread,
        error: Throwable,
        handToAndroid: () -> Unit,
    ) {
        try {
            val now = System.currentTimeMillis()
            val action =
                CrashPolicy.decide(
                    error = error,
                    onScreenThread = thread === Looper.getMainLooper().thread,
                    fromCoroutine = CoroutineFailureMark.isMarked(error),
                    // A plain read of the state, safe from any thread.
                    onScreen =
                        ProcessLifecycleOwner
                            .get()
                            .lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED),
                    lastRestartMs = lastRestart(dir),
                    nowMs = now,
                )
            when (action) {
                CrashPolicy.Action.KEEP_GOING -> {
                    AppLog.e(TAG, "Caught a failure on ${thread.name}; the app carries on", error)
                    if (now - lastRecoveredMs >= RECOVERED_GAP_MS) {
                        lastRecoveredMs = now
                        write(dir, "crash", now) { report(now, thread, error, "caught a failure and kept going") }
                    }
                }

                CrashPolicy.Action.RESTART -> {
                    AppLog.e(TAG, "The screen thread failed; restarting", error)
                    write(dir, "crash", now) { report(now, thread, error, "crashed and restarted") }
                    restart(app, dir, now)
                }

                CrashPolicy.Action.HAND_TO_ANDROID -> {
                    save(dir, thread, error)
                    handToAndroid()
                }
            }
        } catch (second: Throwable) {
            error.addSuppressed(second)
            handToAndroid()
        }
    }

    /** Opens the home screen in a fresh process: the activity start is handed to Android before this one ends. */
    private fun restart(
        app: Application,
        dir: File,
        now: Long,
    ) {
        attempt(TAG, "Couldn't note the restart") {
            dir.mkdirs()
            File(dir, RESTART_MARK).writeText(now.toString())
        }
        app.startActivity(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        Process.killProcess(Process.myPid())
        exitProcess(RESTART_EXIT)
    }

    private fun lastRestart(dir: File): Long = File(dir, RESTART_MARK)
        .takeIf { it.exists() }
        ?.let { attempt(TAG, "Couldn't read the last restart") { it.readText().trim().toLong() }.getOrNull() }
        ?: 0L

    /** Newest first. Reads files, so call it off the main thread. */
    fun reports(context: Context): List<CrashReport> = files(File(context.filesDir, DIR)).mapNotNull { file ->
        attempt(TAG, "Couldn't read a crash report") { CrashReport(file.lastModified(), file.readText()) }.getOrNull()
    }

    /** When each saved freeze happened, from the file names, without reading them. Lists a folder: off the main thread. */
    fun freezeTimes(context: Context): List<Long> = files(File(context.filesDir, DIR))
        .filter { it.name.startsWith("freeze-") }
        .mapNotNull {
            it.name
                .removePrefix("freeze-")
                .removeSuffix(".txt")
                .toLongOrNull()
        }

    /**
     * Crashes and freezes saved after [sinceMs], as ("crash" or "freeze", when), from the file names, for the usage
     * stats' count ([UsageStats]); never what was in them. Lists a folder: off the main thread.
     */
    fun savedSince(
        context: Context,
        sinceMs: Long,
    ): List<Pair<String, Long>> = files(File(context.filesDir, DIR)).mapNotNull { file ->
        val kind = file.name.substringBefore('-')
        val time =
            file.name
                .substringAfter('-')
                .removeSuffix(".txt")
                .toLongOrNull()
        if (time != null && time > sinceMs) kind to time else null
    }

    fun clear(context: Context) {
        files(File(context.filesDir, DIR)).forEach { it.delete() }
    }

    /** Never throws: a failure here must not hide the crash being reported. */
    private fun save(
        dir: File,
        thread: Thread,
        error: Throwable,
    ) {
        val now = System.currentTimeMillis()
        write(dir, "crash", now) { report(now, thread, error, "crashed") }
    }

    /**
     * Saves a freeze report: [what] happened (a sentence), the screen thread's stack at the time ([stack], already
     * text), and the log before it. [timeMs] is when it happened, which for a freeze Android recorded is in the past.
     */
    fun saveFreeze(
        context: Context,
        timeMs: Long,
        what: String,
        stack: String,
    ) = write(File(context.filesDir, DIR), "freeze", timeMs) {
        buildString {
            appendLine("Tuned ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}): $what")
            appendLine("Time: ${TIME.format(Instant.ofEpochMilli(timeMs))}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(AppLog.scrub(stack))
            appendLine("Recent log:")
            AppLog.snapshot().takeLast(RECENT_LINES).forEach { appendLine(it.format()) }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a second failure while crashing has nowhere to go
    private fun write(
        dir: File,
        kind: String,
        timeMs: Long,
        text: () -> String,
    ) {
        try {
            dir.mkdirs()
            File(dir, "$kind-$timeMs.txt").writeText(text())
            files(dir).drop(KEEP).forEach { it.delete() }
        } catch (_: Throwable) {
            // Out of space or memory: the system's own crash log still has it.
        }
    }

    private fun report(
        now: Long,
        thread: Thread,
        error: Throwable,
        what: String,
    ): String = buildString {
        appendLine("Tuned ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) $what")
        appendLine("Time: ${TIME.format(Instant.ofEpochMilli(now))}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Thread: ${thread.name}")
        appendLine()
        appendLine(AppLog.scrub(error.stackTraceToString()))
        appendLine("Recent log:")
        AppLog.snapshot().takeLast(RECENT_LINES).forEach { appendLine(it.format()) }
    }

    private fun files(dir: File): List<File> = dir
        .listFiles { f -> f.isFile && (f.name.startsWith("crash-") || f.name.startsWith("freeze-")) }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
}
