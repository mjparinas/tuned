package com.tunedplayer.app.diagnostics

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Report a problem (Melvin, 2026-11-19) and the Report button on Logs (2026-11-21): both send what a fix needs to
 * Tuned's `POST /api/report`, where it shows in the admin's Reports. Since 2026-11-21 (Melvin: "it should automatically
 * send the logs, as well as other information, as much as we can, non-identifying information to our server") both send
 * the same things: the person's words if any, [DeviceDetails] (logins masked), the last few crash or freeze reports and
 * the whole log. Every log line and saved report has already been through [AppLog.scrub], so none of it carries a login.
 */
object ProblemReport {
    private const val TAG = "Report"
    private const val REPORT_CHARS = 40_000

    /**
     * Send to Tuned from Settings › Report a problem: [what] the person wrote, an email for a reply if they gave one, and
     * everything [sendLogsToTuned] sends. Returns whether it arrived. Call it off the main thread.
     */
    suspend fun sendToTuned(
        context: Context,
        http: OkHttpClient,
        base: String,
        what: String,
        contact: String,
        install: String?,
    ): Boolean {
        val site = base.trim().trimEnd('/')
        if (site.isEmpty()) return false
        val details = DeviceDetails.collect(context)
        val crashes =
            CrashReporter
                .reports(context)
                .take(CRASHES)
                .joinToString("\n---- Earlier ----\n") { it.text }
                .take(REPORT_CHARS)
        val log =
            AppLog
                .snapshot()
                .joinToString("\n") { it.format() }
                .takeLast(LOG_CHARS)
        val body =
            Json.encodeToString(
                Sent(
                    install = install,
                    device = UsageStats.device(context),
                    what = what.trim().take(WHAT_CHARS),
                    crash = crashes,
                    log = log,
                    contact = contact.trim().take(CONTACT_CHARS),
                    details = details,
                ),
            )
        return post(http, site, body)
    }

    /**
     * The Report button on Logs (Melvin, 2026-11-21: "on the logs screen, there should be a report button and it should
     * automatically send all of the users logs, and all of their device information to me"): nothing to type, so it
     * works with a TV remote. Everything [sendToTuned] sends, with no words. Returns whether it arrived. Call it off the
     * main thread.
     */
    suspend fun sendLogsToTuned(
        context: Context,
        http: OkHttpClient,
        base: String,
        install: String?,
    ): Boolean = sendToTuned(context, http, base, what = "", contact = "", install = install)

    private fun post(
        http: OkHttpClient,
        site: String,
        body: String,
    ): Boolean = attempt(TAG, "Couldn't send the report to Tuned") {
        val request =
            Request
                .Builder()
                .url("$site/api/report")
                .post(body.toRequestBody(JSON))
                .build()
        http.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    @Serializable
    private data class Sent(
        val install: String?,
        val device: UsageStats.Device,
        val what: String,
        val crash: String,
        val log: String,
        val contact: String,
        /** [DeviceDetails], logins masked. */
        val details: String = "",
    )

    private const val WHAT_CHARS = 4_000
    private const val CONTACT_CHARS = 200

    /** All of the log (1,000 lines at most), within what the server keeps. */
    private const val LOG_CHARS = 150_000
    private const val CRASHES = 3
    private val JSON = "application/json".toMediaType()
}
