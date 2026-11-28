package com.tunedplayer.app.diagnostics

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.system.exitProcess

/**
 * Settings › Updates and advanced › Record a speed trace (Melvin, 2026-11-28: "a one-tap debug trace to find slow spots
 * that only show on real hardware"). One tap restarts Tuned and records its next [LENGTH_MS] from the moment the
 * process starts: the time to each step of the start (the app built, the first screen, Home drawn) and to each picture
 * after a channel change, plus Android's own sampled method trace. The timings go to Logs, where Share and Report a
 * problem already carry them; the method trace stays on the device for `adb pull` and Android Studio's profiler
 * (the path is in the timings). Nothing is recorded unless it was asked for, and [mark] costs one check otherwise.
 */
object SpeedTrace {
    /** Where the last trace and the request for the next one live (cleared by sign-out with the rest of the cache). */
    private const val DIR = "speed-trace"
    private const val ARMED = "armed"
    private const val TAG = "SpeedTrace"
    const val LENGTH_MS = 20_000L
    private const val SAMPLE_US = 1_000
    private const val BUFFER_BYTES = 16 * 1024 * 1024

    sealed interface State {
        data object Idle : State

        /** Recording, with the moment (elapsed realtime) it ends. */
        data class Recording(
            val endsAt: Long,
        ) : State

        /** The last trace's time from the process starting to Home's first frame, or null if Home never drew. */
        data class Done(
            val homeMs: Long?,
        ) : State
    }

    private val state = MutableStateFlow<State>(State.Idle)
    val status: StateFlow<State> = state.asStateFlow()

    @Volatile private var recording = false
    private var startedAt = 0L
    private var tracePath = ""
    private val marks = ArrayList<SpeedTraceReport.Mark>()

    /** Settings' button: ask for a trace of the next start, and restart now so it begins at once. */
    fun recordNextStart(context: Context) {
        val app = context.applicationContext
        attempt(TAG, "Couldn't ask for a speed trace") {
            dir(app).mkdirs()
            File(dir(app), ARMED).writeText(System.currentTimeMillis().toString())
        }.onFailure { return }
        AppLog.i(TAG) { "Restarting to record a speed trace" }
        app.startActivity(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    /** First thing in [Application.onCreate]: starts recording when the last run asked for it. One file check otherwise. */
    fun startIfAsked(app: Application) {
        val armed = File(dir(app), ARMED)
        if (!armed.exists()) return
        armed.delete()
        // From the process's own start, so the time before the app's code runs counts too.
        startedAt = Process.getStartElapsedRealtime()
        recording = true
        state.value = State.Recording(startedAt + LENGTH_MS)
        mark("Tuned starts running")
        attempt(TAG, "Couldn't start the method trace") {
            val out = app.getExternalFilesDir(DIR) ?: dir(app)
            out.mkdirs()
            tracePath = File(out, "start.trace").path
            Debug.startMethodTracingSampling(tracePath, BUFFER_BYTES, SAMPLE_US)
        }
        val left = (startedAt + LENGTH_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, left)
    }

    /** Notes that [what] happened now, while a trace records. */
    fun mark(what: String) {
        if (!recording) return
        synchronized(marks) { marks += SpeedTraceReport.Mark(what, SystemClock.elapsedRealtime() - startedAt) }
    }

    /** Notes how long [what] took, ending now, while a trace records. */
    fun took(
        what: String,
        ms: Long,
    ) {
        if (!recording) return
        synchronized(marks) { marks += SpeedTraceReport.Mark(what, SystemClock.elapsedRealtime() - startedAt, ms) }
    }

    private fun finish() {
        if (!recording) return
        recording = false
        attempt(TAG, "Couldn't stop the method trace") { Debug.stopMethodTracing() }
        val taken = synchronized(marks) { marks.toList().also { marks.clear() } }
        val report =
            SpeedTraceReport.format(
                device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
                version = BuildConfig.VERSION_NAME,
                marks = taken,
                tracePath = tracePath,
            )
        report.lines().forEach { line -> AppLog.i(TAG) { line } }
        state.value = State.Done(SpeedTraceReport.homeMs(taken))
    }

    private fun dir(context: Context) = File(context.noBackupFilesDir, DIR)

    /** Sign-out: the request and the last method trace go with everything else. */
    fun clear(context: Context) {
        dir(context).deleteRecursively()
        context.getExternalFilesDir(DIR)?.deleteRecursively()
    }
}

/** The text a speed trace leaves in Logs; plain Kotlin, so it is tested on the JVM. */
object SpeedTraceReport {
    /** Something that happened [atMs] after the process started, taking [tookMs] when it is a span. */
    data class Mark(
        val what: String,
        val atMs: Long,
        val tookMs: Long? = null,
    )

    const val HOME = "Home drawn"

    fun homeMs(marks: List<Mark>): Long? = marks.firstOrNull { it.what == HOME }?.atMs

    fun format(
        device: String,
        version: String,
        marks: List<Mark>,
        tracePath: String,
    ): String = buildString {
        appendLine("Speed trace, Tuned $version on $device")
        for (m in marks.sortedBy { it.atMs }) {
            append("  ").append(seconds(m.atMs)).append("  ").append(m.what)
            m.tookMs?.let { append(" (").append(it).append(" ms)") }
            appendLine()
        }
        val spans = marks.mapNotNull { it.tookMs }
        if (spans.isNotEmpty()) appendLine("  Slowest span: ${spans.max()} ms; average ${spans.average().toLong()} ms over ${spans.size}")
        append("  Method trace: ").append(tracePath.ifBlank { "not saved" })
    }

    private fun seconds(ms: Long): String {
        val whole = ms / MS_PER_S
        val part = (ms % MS_PER_S).toString().padStart(3, '0')
        return "$whole.${part}s"
    }

    private const val MS_PER_S = 1_000
}
