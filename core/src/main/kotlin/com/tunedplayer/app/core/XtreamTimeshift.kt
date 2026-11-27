package com.tunedplayer.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A recording on a catch-up channel: [minutes] from [startMs], which the server reads in its own time zone
 * ([zone], from the account answer).
 */
fun XtreamUrls.timeshift(
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
