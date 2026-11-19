package com.tunedplayer.app.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.abs

/**
 * Whether the device's clock is plainly wrong (Melvin, 2026-11-19, from the Horizon Maps review). Cheap Android TV boxes
 * often lose the date when unplugged. A secure connection then fails as if the server were down, because its certificate
 * looks expired or not yet valid, so TMDB, sync, updates and any HTTPS playlist all fail with nothing to say why.
 *
 * The clock looks wrong when it is before this version could have been made ([EARLIEST_MS]), or when a server's own
 * time (the HTTP `Date` header, which plain-HTTP playlist servers still send) is more than [TOLERANCE_MS] away.
 */
object ClockCheck {
    /** 1 November 2026, before any version with this check. A device earlier than this has the wrong date. */
    const val EARLIEST_MS = 1_793_491_200_000L

    /** A day: time zones and a server with a sloppy clock stay well inside it; a lost date doesn't. */
    const val TOLERANCE_MS = 24L * 60 * 60 * 1_000

    /**
     * True when the device's time, [deviceMs], is plainly wrong. [serverSkewMs] is how far a server's time was from the
     * device's at the last answer (server minus device), or null when no server has said.
     */
    fun looksWrong(
        deviceMs: Long,
        serverSkewMs: Long?,
    ): Boolean = deviceMs < EARLIEST_MS || (serverSkewMs != null && abs(serverSkewMs) > TOLERANCE_MS)

    /** An HTTP `Date` header ("Thu, 19 Nov 2026 01:50:00 GMT") in milliseconds, or null when it isn't one. */
    fun httpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
