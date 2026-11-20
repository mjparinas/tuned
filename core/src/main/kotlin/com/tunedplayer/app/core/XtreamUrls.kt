package com.tunedplayer.app.core

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Where an Xtream Codes server keeps its playlist and guide, and how to keep login details out of logs. */
object XtreamUrls {
    fun normalizeServer(server: String): String {
        val trimmed = server.trim().trimEnd('/')
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "http://$trimmed"
    }

    /** `m3u_plus` carries the categories, logos and guide ids; `ts` streams start fastest on Media3. */
    fun playlist(
        server: String,
        username: String,
        password: String,
    ): String = "${normalizeServer(server)}/get.php?username=${enc(username)}&password=${enc(password)}&type=m3u_plus&output=ts"

    fun guide(
        server: String,
        username: String,
        password: String,
    ): String = "${normalizeServer(server)}/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    /** The account check: a small JSON answer whose `user_info.auth` is 1 for a good login. */
    fun account(
        server: String,
        username: String,
        password: String,
    ): String = "${normalizeServer(server)}/player_api.php?username=${enc(username)}&password=${enc(password)}"

    /** Every live channel with its catch-up details (`tv_archive`, `tv_archive_duration`). */
    fun liveStreams(
        server: String,
        username: String,
        password: String,
    ): String = account(server, username, password) + "&action=get_live_streams"

    /**
     * A recording on a catch-up channel: [minutes] from [startMs], which the server reads in its own time zone
     * ([zone], from the account answer).
     */
    fun timeshift(
        server: String,
        username: String,
        password: String,
        streamId: String,
        startMs: Long,
        minutes: Int,
        zone: ZoneId,
    ): String {
        val start = TIMESHIFT_START.format(Instant.ofEpochMilli(startMs).atZone(zone))
        return "${normalizeServer(server)}/timeshift/${enc(username)}/${enc(password)}/$minutes/$start/$streamId.ts"
    }

    private val TIMESHIFT_START: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

    /** Replaces the username and password wherever they appear (query or path), for error messages and logs. */
    fun redact(
        text: String,
        username: String,
        password: String,
    ): String {
        var out = text
        for (secret in listOf(password, enc(password), username, enc(username))) {
            if (secret.length >= 2) out = out.replace(secret, "•••")
        }
        return out
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
