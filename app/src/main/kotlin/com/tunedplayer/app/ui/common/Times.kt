package com.tunedplayer.app.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.tunedplayer.app.core.TimeZones
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone
import android.icu.text.SimpleDateFormat as IcuDateFormat
import android.icu.util.TimeZone as IcuTimeZone
import android.text.format.DateFormat as AndroidDateFormat

/** The zone guide and event times show in: Settings › Guide › Time zone, which follows the phone by default. */
val LocalTimeZone = staticCompositionLocalOf<ZoneId> { ZoneId.systemDefault() }

/**
 * Guide and event times in the chosen zone, in the phone's 12- or 24-hour style: "9:00 p.m.", "Wed 9:00 p.m.",
 * "Wed", "Nov 18, 2026", and the zone's short name ("PST") so it's clear which zone they're in.
 */
class TimeLabels(
    context: Context,
    val zone: ZoneId,
) {
    private val locale = context.resources.configuration.locales[0]
    private val tz = TimeZone.getTimeZone(zone)
    private val hours = if (AndroidDateFormat.is24HourFormat(context)) "Hm" else "hma"
    private val timeFormat = AndroidDateFormat.getTimeFormat(context).apply { timeZone = tz }
    private val dayTimeFormat = pattern("EEE$hours")
    private val dayFormat = pattern("EEE")
    private val dateFormat = AndroidDateFormat.getMediumDateFormat(context).apply { timeZone = tz }

    // ICU's formatter, the one getBestDateTimePattern writes for: its patterns can use ICU-only letters ("ccc" for a
    // stand-alone weekday), which java.text.SimpleDateFormat off a device (the screenshots) rejects.
    private fun pattern(skeleton: String) = IcuDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, skeleton), locale).apply {
        timeZone = IcuTimeZone.getTimeZone(zone.id)
    }

    fun time(at: Long): String = timeFormat.format(Date(at))

    /** "Wed 9:00 p.m.": the weekday cut to three letters (Melvin, 2026-11-18). */
    fun dayTime(at: Long): String = dayTimeFormat.format(Date(at))

    fun day(at: Long): String = dayFormat.format(Date(at))

    fun date(at: Long): String = dateFormat.format(Date(at))

    fun isToday(
        at: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean = TimeZones.sameDay(at, now, zone)

    /** "PST" now, "PDT" in summer. */
    fun zoneName(at: Long = System.currentTimeMillis()): String = TimeZones.shortName(zone, at, locale)
}

@Composable
fun rememberTimeLabels(): TimeLabels {
    val context = LocalContext.current
    val zone = LocalTimeZone.current
    return remember(context, zone) { TimeLabels(context, zone) }
}
