package com.tunedplayer.app.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.R
import com.tunedplayer.app.work.TvHome
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Report a problem (Melvin, 2026-11-19, from the Horizon Maps review): one tap gathers what a fix needs, the version
 * and build, the device, the latest crash or freeze report and the recent log, and hands it to the share sheet (email,
 * chat, a note). Every line in it has already been through [AppLog.scrub] (log lines as they're written, reports as
 * they're saved), so it carries no login. Nothing is sent anywhere unless the person picks where it goes: the share
 * sheet, or Tuned itself ([sendToTuned]).
 */
object ProblemReport {
    private const val TAG = "Report"
    private const val LOG_LINES = 400
    private const val REPORT_CHARS = 40_000

    /** Well under the 1 MB a share or the clipboard can pass between apps. */
    private const val MAX_CHARS = 200_000

    /** The report as plain text. Reads the saved reports, so call it off the main thread. */
    fun build(context: Context): String {
        val latest = CrashReporter.reports(context).firstOrNull()
        val text =
            buildString {
                appendLine("Tuned problem report")
                appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                if (BuildConfig.GIT_COMMIT.isNotEmpty()) appendLine("Build: ${BuildConfig.GIT_COMMIT}")
                appendLine("From: ${if (BuildConfig.GITHUB_UPDATES) "GitHub" else "store"}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}${if (TvHome.isTv(context)) " (TV)" else ""}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine()
                appendLine("What happened (write it here):")
                appendLine()
                appendLine()
                appendLine("---- Latest crash or freeze ----")
                appendLine(latest?.text?.take(REPORT_CHARS) ?: "None saved.")
                appendLine("---- Recent log, oldest first ----")
                AppLog.snapshot().takeLast(LOG_LINES).forEach { appendLine(it.format()) }
            }
        return text.take(MAX_CHARS)
    }

    /**
     * Opens the share sheet with [report]. A device with nothing to share to (some Android TVs) gets it copied to the
     * clipboard instead. Says which happened, so the screen can tell the person.
     */
    fun send(
        context: Context,
        report: String,
    ): Outcome {
        val subject = context.getString(R.string.report_subject, BuildConfig.VERSION_NAME)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, report)
        if (context.startActivitySafely(Intent.createChooser(send, subject), TAG)) return Outcome.SHARED
        val copied =
            attempt(TAG, "Couldn't copy the report") {
                val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("No clipboard")
                clipboard.setPrimaryClip(ClipData.newPlainText(subject, report))
            }.isSuccess
        return if (copied) Outcome.COPIED else Outcome.FAILED
    }

    /**
     * Send to Tuned (docs/decisions/admin.md): [what] the person wrote, an email for a reply if they gave one, the
     * latest crash or freeze, the recent log and the device, to `POST /api/report`, where it shows in the admin's
     * Reports. The log and reports were scrubbed of logins as they were written. Returns whether it arrived. Call it
     * off the main thread.
     */
    fun sendToTuned(
        context: Context,
        http: OkHttpClient,
        base: String,
        what: String,
        contact: String,
        install: String?,
    ): Boolean {
        val site = base.trim().trimEnd('/')
        if (site.isEmpty()) return false
        val body =
            Json.encodeToString(
                Sent(
                    install = install,
                    device = UsageStats.device(context),
                    what = what.trim().take(WHAT_CHARS),
                    crash =
                        CrashReporter
                            .reports(context)
                            .firstOrNull()
                            ?.text
                            ?.take(REPORT_CHARS)
                            .orEmpty(),
                    log =
                        AppLog
                            .snapshot()
                            .takeLast(LOG_LINES)
                            .joinToString("\n") { it.format() }
                            .takeLast(SENT_LOG_CHARS),
                    contact = contact.trim().take(CONTACT_CHARS),
                ),
            )
        return attempt(TAG, "Couldn't send the report to Tuned") {
            val request =
                Request
                    .Builder()
                    .url("$site/api/report")
                    .post(body.toRequestBody(JSON))
                    .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    @Serializable
    private data class Sent(
        val install: String?,
        val device: UsageStats.Device,
        val what: String,
        val crash: String,
        val log: String,
        val contact: String,
    )

    private const val WHAT_CHARS = 4_000
    private const val SENT_LOG_CHARS = 60_000
    private const val CONTACT_CHARS = 200
    private val JSON = "application/json".toMediaType()

    enum class Outcome { SHARED, COPIED, FAILED }
}
