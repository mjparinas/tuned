package app.tuned.diagnostics

import android.util.Log
import app.tuned.BuildConfig
import app.tuned.core.LogScrubber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LogLevel(
    val letter: Char,
) {
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

data class LogEntry(
    val timeMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val thread: String,
) {
    fun format(): String = "${TIME.format(Instant.ofEpochMilli(timeMs))} ${level.letter}/$tag [$thread] $message"

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}

/**
 * The app's own log, kept in memory for Settings › Developer › Logs and for crash reports ([CrashReporter]).
 * Warnings and errors are always kept (a few hundred lines cost nothing); debug and info lines only in developer
 * mode ([verbose]), and their text is built lazily so they cost nothing when it's off.
 *
 * Every line goes through [scrub] first: the login in use and any login-shaped part of an address become `•••`,
 * so a log can be shared without giving the account away (docs/decisions/security.md).
 */
object AppLog {
    private const val MAX_ENTRIES = 1_000
    private const val MAX_MESSAGE = 8_000
    private const val MASK = "•••"

    private val lock = Any()
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _changes = MutableStateFlow(0L)

    /** Ticks on every new line, so an open log viewer can refresh. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** Developer mode: also keep debug and info lines. */
    @Volatile var verbose: Boolean = false

    @Volatile private var secrets: List<String> = emptyList()

    /** The login in use, so it is masked even where it doesn't look like one. Values shorter than 3 are ignored. */
    fun setSecrets(vararg values: String) {
        secrets = values.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }
    }

    fun d(
        tag: String,
        message: () -> String,
    ) {
        if (verbose || BuildConfig.DEBUG) add(LogLevel.DEBUG, tag, message(), null)
    }

    fun i(
        tag: String,
        message: () -> String,
    ) {
        if (verbose || BuildConfig.DEBUG) add(LogLevel.INFO, tag, message(), null)
    }

    fun w(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = add(LogLevel.WARN, tag, message, error)

    fun e(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) = add(LogLevel.ERROR, tag, message, error)

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
        _changes.value++
    }

    /** Masks the login in use and anything login-shaped. */
    fun scrub(text: String): String {
        var out = LogScrubber.scrub(text)
        for (secret in secrets) out = out.replace(secret, MASK)
        return out
    }

    private fun add(
        level: LogLevel,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        val full = if (error == null) message else "$message\n${error.stackTraceToString()}"
        val entry = LogEntry(System.currentTimeMillis(), level, tag, scrub(full).take(MAX_MESSAGE), Thread.currentThread().name)
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) entries.removeFirst()
            entries.addLast(entry)
        }
        _changes.value++
        if (BuildConfig.DEBUG) logcat(entry)
    }

    private fun logcat(entry: LogEntry) {
        val tag = "Tuned/${entry.tag}"
        when (entry.level) {
            LogLevel.DEBUG -> Log.d(tag, entry.message)
            LogLevel.INFO -> Log.i(tag, entry.message)
            LogLevel.WARN -> Log.w(tag, entry.message)
            LogLevel.ERROR -> Log.e(tag, entry.message)
        }
    }
}
