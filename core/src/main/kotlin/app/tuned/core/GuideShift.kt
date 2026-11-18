package app.tuned.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A playlist's `tvg-shift`: how many hours a channel runs behind or ahead of its guide, as on a west-coast feed
 * that shares the east-coast guide. The shift is kept in the channel's guide id (`cnn.us@shift+180`), and the guide
 * import writes that channel's programmes under that id with the times moved, so every screen that matches a
 * channel to its guide by id shows the right times without knowing about shifts.
 */
object GuideShift {
    private const val MARK = "@shift"
    private const val MINUTES_PER_HOUR = 60
    private const val MAX_HOURS = 24
    private const val MILLIS_PER_MINUTE = 60_000L

    /** [tvgId] with [shift] (hours, such as `+3`, `-1.5` or `5:30`) kept in it; unchanged when there is no shift. */
    fun tag(
        tvgId: String?,
        shift: String?,
    ): String? {
        if (tvgId == null) return null
        val minutes = minutes(shift) ?: return tvgId
        return if (minutes == 0L) tvgId else "$tvgId$MARK${if (minutes > 0) "+" else ""}$minutes"
    }

    /** The guide id that a tagged id reads its programmes from. */
    fun base(id: String): String = id.substringBefore(MARK)

    /** How far to move a tagged id's programmes, in milliseconds. */
    fun offset(id: String): Long {
        val at = id.indexOf(MARK)
        if (at < 0) return 0
        return id.substring(at + MARK.length).toLongOrNull()?.times(MILLIS_PER_MINUTE) ?: 0
    }

    internal fun minutes(shift: String?): Long? {
        val text = shift?.trim()?.removePrefix("+")?.takeIf { it.isNotEmpty() } ?: return null
        val hours =
            if (':' in text) {
                val negative = text.startsWith("-")
                val (h, m) = text.removePrefix("-").split(':', limit = 2)
                val total = (h.toIntOrNull() ?: return null) + (m.toIntOrNull() ?: return null) / MINUTES_PER_HOUR.toDouble()
                if (negative) -total else total
            } else {
                text.replace(',', '.').toDoubleOrNull() ?: return null
            }
        if (hours.isNaN() || abs(hours) > MAX_HOURS) return null
        return (hours * MINUTES_PER_HOUR).roundToLong()
    }
}
