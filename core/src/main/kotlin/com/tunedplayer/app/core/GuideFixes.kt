package com.tunedplayer.app.core

/**
 * Guide fixes (Melvin, 2026-11-17, from the app audit: wrong or missing guide data is one of the most common
 * complaints). Three of them:
 * - a time offset for the whole guide, for a guide that is an hour or more out;
 * - a time offset for one channel, for a channel that runs on another time zone's feed;
 * - a guide entry picked by hand, for a channel the guide didn't match or matched wrongly.
 *
 * A channel with its own fix gets its own guide id, [fixedId], in place of the playlist's tvg-id. The guide import
 * copies its source's programmes under that id, moved by its offset, so every screen that reads the guide (lists,
 * the channel's guide, search, Live Events) shows the fixed times without knowing about fixes.
 */
object GuideFixes {
    const val PREFIX = "fix:"

    /** Offsets on offer, in minutes: half an hour either way, then whole hours up to 12. */
    val OFFSETS: List<Int> = (-12..-1).map { it * 60 } + listOf(-30, 0, 30) + (1..12).map { it * 60 }

    data class Fix(
        val itemId: Long,
        /** The guide channel it takes programmes from: the one picked by hand, or else the playlist's own. */
        val source: String,
        val offsetMinutes: Int,
    )

    fun fixedId(itemId: Long): String = "$PREFIX$itemId"

    fun isFixed(tvgId: String?): Boolean = tvgId?.startsWith(PREFIX) == true

    /** For the import: each source guide id, and the fixed ids to copy its programmes to, with how far to move them. */
    fun targets(fixes: List<Fix>): Map<String, List<Pair<String, Long>>> =
        fixes.groupBy({ it.source }) { fixedId(it.itemId) to it.offsetMinutes * MINUTE }

    /** "+1:30", "−2:00", "0:00". */
    fun label(minutes: Int): String {
        val sign =
            when {
                minutes > 0 -> "+"
                minutes < 0 -> "−"
                else -> ""
            }
        val m = kotlin.math.abs(minutes)
        return "$sign${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    const val MINUTE = 60_000L
}
