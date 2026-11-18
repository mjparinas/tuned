package app.tuned.core

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The time zone guide and event times are shown in (Settings › Guide › Time zone). Guide times are stored as instants
 * (XMLTV carries its own offset), so a zone only changes how they read: "Wed 9:00 p.m. PST" on a phone in Vancouver.
 */
object TimeZones {
    /** Stored when the setting follows the phone's own zone, which is the default. */
    const val DEVICE = ""

    /** The zones offered besides the device's, west to east. One city per common zone, so the list stays short. */
    val CHOICES: List<String> =
        listOf(
            "Pacific/Honolulu",
            "America/Anchorage",
            "America/Vancouver",
            "America/Los_Angeles",
            "America/Edmonton",
            "America/Denver",
            "America/Phoenix",
            "America/Regina",
            "America/Winnipeg",
            "America/Chicago",
            "America/Mexico_City",
            "America/Toronto",
            "America/New_York",
            "America/Halifax",
            "America/St_Johns",
            "America/Sao_Paulo",
            "America/Argentina/Buenos_Aires",
            "UTC",
            "Europe/London",
            "Europe/Dublin",
            "Europe/Lisbon",
            "Africa/Lagos",
            "Europe/Paris",
            "Europe/Berlin",
            "Europe/Madrid",
            "Europe/Rome",
            "Europe/Amsterdam",
            "Africa/Johannesburg",
            "Africa/Cairo",
            "Europe/Athens",
            "Europe/Istanbul",
            "Asia/Dubai",
            "Asia/Karachi",
            "Asia/Kolkata",
            "Asia/Dhaka",
            "Asia/Bangkok",
            "Asia/Manila",
            "Asia/Singapore",
            "Asia/Hong_Kong",
            "Asia/Shanghai",
            "Asia/Tokyo",
            "Asia/Seoul",
            "Australia/Perth",
            "Australia/Brisbane",
            "Australia/Sydney",
            "Pacific/Auckland",
        )

    /** The zone for a stored setting: blank, or a zone this phone doesn't know, follows the device. */
    fun resolve(
        id: String,
        device: ZoneId = ZoneId.systemDefault(),
    ): ZoneId {
        if (id.isBlank()) return device
        return try {
            ZoneId.of(id)
        } catch (_: DateTimeException) {
            device
        }
    }

    /** The start of the day after [now]'s day in [zone]: where "Later today" ends and "Tomorrow" begins. */
    fun nextMidnight(
        now: Long,
        zone: ZoneId,
    ): Long = Instant
        .ofEpochMilli(now)
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    /** Whether two times fall on the same calendar day in [zone]. */
    fun sameDay(
        a: Long,
        b: Long,
        zone: ZoneId,
    ): Boolean = Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

    /** The zone's short name at [at], such as "PST" or "PDT" (or "GMT+1" where there's no common abbreviation). */
    fun shortName(
        zone: ZoneId,
        at: Long,
        locale: Locale,
    ): String = if (zone.normalized() == ZoneOffset.UTC) {
        "UTC"
    } else {
        DateTimeFormatter.ofPattern("zzz", locale).withZone(zone).format(Instant.ofEpochMilli(at))
    }

    /** The offset from UTC at [at]: "UTC−8", "UTC+5:30", "UTC". */
    fun offsetLabel(
        zone: ZoneId,
        at: Long,
    ): String {
        val seconds = zone.rules.getOffset(Instant.ofEpochMilli(at)).totalSeconds
        if (seconds == 0) return "UTC"
        val sign = if (seconds < 0) "−" else "+"
        val minutes = kotlin.math.abs(seconds) / 60
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) "UTC$sign$hours" else "UTC$sign$hours:${rest.toString().padStart(2, '0')}"
    }

    /** A choice as the settings list shows it: "Vancouver · PST (UTC−8)". */
    fun label(
        id: String,
        at: Long,
        locale: Locale,
    ): String {
        val zone = resolve(id)
        val city = id.substringAfterLast('/').replace('_', ' ')
        val short = shortName(zone, at, locale)
        val offset = offsetLabel(zone, at)
        return when {
            city == "UTC" -> "UTC"
            short.startsWith("GMT") || short.startsWith("UTC") -> "$city · $offset"
            else -> "$city · $short ($offset)"
        }
    }
}
