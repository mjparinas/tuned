package com.tunedplayer.app.diagnostics

import android.content.Context
import android.os.Build
import com.tunedplayer.app.BuildConfig
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
 * Saves a report of any crash the app didn't handle, with the log lines that led up to it, so it can be read and
 * shared later from Settings › Updates and advanced › Logs. The crash itself still ends the app as it would have (an app left
 * running after one is in an unknown state), but nothing about it is lost. Freezes are saved here too ([FreezeWatch]).
 * Reports stay on the device, at most [KEEP] of them, and carry no login ([AppLog.scrub]).
 */
object CrashReporter {
    private const val DIR = "crashes"
    private const val KEEP = 10
    private const val RECENT_LINES = 200
    private const val TAG = "Crash"
    private val TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    fun install(context: Context) {
        val dir = File(context.filesDir, DIR)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            save(dir, thread, error)
            if (previous != null) previous.uncaughtException(thread, error) else exitProcess(2)
        }
    }

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
        write(dir, "crash", now) { report(now, thread, error) }
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
    ): String = buildString {
        appendLine("Tuned ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) crashed")
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
