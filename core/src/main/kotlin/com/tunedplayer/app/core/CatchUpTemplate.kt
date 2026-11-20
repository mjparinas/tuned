package com.tunedplayer.app.core

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a plain M3U channel's catch-up link for one programme ([M3uCatchUp]), the way Kodi's IPTV Simple and
 * TiviMate do, so playlists written for them work unchanged. Times in the template are Unix seconds; dates are in
 * [ZoneId] (the server's, when known).
 */
object CatchUpTemplate {
    private const val SECOND = 1000L
    private val placeholder = Regex("""\$?\{([^{}]+)\}""")
    private val xtreamLive = Regex("""^(https?://[^/]+)(?:/live)?/([^/]+)/([^/]+)/(\d+)(\.[A-Za-z0-9]+)?$""")
    private val hlsName = Regex("""/([^/?]+)\.m3u8$""")

    /** The recording's link, keeping the live link's headers; null when the live link can't make one. */
    fun link(
        liveLink: String,
        catchUp: M3uCatchUp,
        startMs: Long,
        stopMs: Long,
        nowMs: Long,
        zone: ZoneId,
    ): String? {
        val parts = StreamLink.split(liveLink)
        val live = parts.url
        val times = Times(startMs / SECOND, stopMs / SECOND, nowMs / SECOND, zone)
        val address =
            when (catchUp.mode) {
                M3uCatchUp.DEFAULT -> catchUp.source?.let { fill(it, times) }
                M3uCatchUp.APPEND -> catchUp.source?.let { live + fill(it, times) }
                M3uCatchUp.SHIFT -> live + (if ('?' in live) "&" else "?") + "utc=${times.start}&lutc=${times.now}"
                M3uCatchUp.FLUSSONIC -> catchUp.source?.let { fill(it, times) } ?: flussonic(live, times)
                M3uCatchUp.XC -> xtream(live, startMs, stopMs, zone)
                else -> null
            } ?: return null
        return StreamLink.join(address, parts.headers)
    }

    /** Flussonic: `…/index.m3u8` becomes `…/index-<start>-<duration>.m3u8`, and `…/mpegts` becomes `…/timeshift_abs-<start>.ts`. */
    private fun flussonic(
        live: String,
        t: Times,
    ): String? {
        val path = live.substringBefore('?')
        val query = live.substring(path.length)
        return when {
            path.endsWith("/mpegts") -> path.removeSuffix("/mpegts") + "/timeshift_abs-${t.start}.ts" + query
            hlsName.containsMatchIn(path) -> hlsName.replace(path) { "/${it.groupValues[1]}-${t.start}-${t.duration}.m3u8" } + query
            else -> null
        }
    }

    /** Xtream's timeshift link, from a live link of the `/live/user/pass/id.ts` shape. */
    private fun xtream(
        live: String,
        startMs: Long,
        stopMs: Long,
        zone: ZoneId,
    ): String? {
        val m = xtreamLive.find(live.substringBefore('?')) ?: return null
        val g = m.groupValues
        val server = g[1]
        val path = "${g[2]}/${g[3]}"
        val id = g[4]
        val ext = g[5].ifEmpty { ".ts" }
        val start = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT).format(Instant.ofEpochMilli(startMs).atZone(zone))
        return "$server/timeshift/$path/${CatchUp.minutes(startMs, stopMs)}/$start/$id$ext"
    }

    private class Times(
        val start: Long,
        val end: Long,
        val now: Long,
        zone: ZoneId,
    ) {
        val duration = (end - start).coerceAtLeast(1)
        val startDate: ZonedDateTime = Instant.ofEpochSecond(start).atZone(zone)
        val endDate: ZonedDateTime = Instant.ofEpochSecond(end).atZone(zone)
        val nowDate: ZonedDateTime = Instant.ofEpochSecond(now).atZone(zone)
    }

    internal fun fill(
        template: String,
        startMs: Long,
        stopMs: Long,
        nowMs: Long,
        zone: ZoneId,
    ): String = fill(template, Times(startMs / SECOND, stopMs / SECOND, nowMs / SECOND, zone))

    private fun fill(
        template: String,
        t: Times,
    ): String = placeholder.replace(template) { m -> value(m.groupValues[1].trim(), t) ?: m.value }

    @Suppress("CyclomaticComplexMethod") // one branch per placeholder name reads best as one table
    private fun value(
        name: String,
        t: Times,
    ): String? {
        val key = name.substringBefore(':')
        val arg = name.substringAfter(':', "")
        return when (key) {
            "utc", "start" -> {
                if (arg.isEmpty()) "${t.start}" else strftime(arg, t.startDate)
            }

            "utcend", "end" -> {
                if (arg.isEmpty()) "${t.end}" else strftime(arg, t.endDate)
            }

            "lutc", "now", "timestamp" -> {
                if (arg.isEmpty()) "${t.now}" else strftime(arg, t.nowDate)
            }

            "duration" -> {
                "${t.duration / (arg.toLongOrNull()?.takeIf { it > 0 } ?: 1)}"
            }

            "offset" -> {
                "${(t.now - t.start).coerceAtLeast(0) / (arg.toLongOrNull()?.takeIf { it > 0 } ?: 1)}"
            }

            "Y", "m", "d", "H", "M", "S" -> {
                strftime(key, t.startDate)
            }

            else -> {
                when {
                    name.startsWith("(b)") -> pattern(name.substring(3), t.startDate)
                    name.startsWith("(e)") -> pattern(name.substring(3), t.endDate)
                    else -> null
                }
            }
        }
    }

    /** `Y-m-d:H-M` style formats: each letter is a strftime field, anything else is copied. */
    private fun strftime(
        format: String,
        d: ZonedDateTime,
    ): String = buildString {
        for (c in format) {
            when (c) {
                'Y' -> append(d.year.toString().padStart(4, '0'))
                'm' -> append(d.monthValue.toString().padStart(2, '0'))
                'd' -> append(d.dayOfMonth.toString().padStart(2, '0'))
                'H' -> append(d.hour.toString().padStart(2, '0'))
                'M' -> append(d.minute.toString().padStart(2, '0'))
                'S' -> append(d.second.toString().padStart(2, '0'))
                else -> append(c)
            }
        }
    }

    /** `${(b)yyyyMMddHHmmss}` style formats, read as Java date patterns. */
    private fun pattern(
        format: String,
        d: ZonedDateTime,
    ): String? = try {
        DateTimeFormatter.ofPattern(format, Locale.ROOT).format(d)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: DateTimeException) {
        null
    }
}
