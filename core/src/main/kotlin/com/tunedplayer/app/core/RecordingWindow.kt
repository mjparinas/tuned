package com.tunedplayer.app.core

/**
 * When a scheduled recording runs (Melvin, 2026-11-20: "do 4, 6, 7, 8"; docs/decisions/player.md): from a little before
 * the programme starts to a little after it ends, since guides run early or late. There is one connection, so two
 * recordings can't overlap: a second one for the same time is refused rather than cutting the first short.
 */
object RecordingWindow {
    /** A recording's time on the connection, from [from] to [until] (epoch ms), and what it is ([key]). */
    data class Slot(
        val key: String,
        val from: Long,
        val until: Long,
    )

    /** A programme with no end in the guide is recorded for this long. */
    const val UNKNOWN_LENGTH_MS = 2 * 60 * 60_000L

    /** Nothing records longer than this, whatever the guide says (a channel listed as one 24-hour programme). */
    const val MAX_LENGTH_MS = 6 * 60 * 60_000L

    private const val MINUTE_MS = 60_000L

    fun slot(
        key: String,
        start: Long,
        stop: Long,
        paddingMinutes: Int,
    ): Slot {
        val pad = paddingMinutes.coerceAtLeast(0) * MINUTE_MS
        val end = if (stop > start) stop else start + UNKNOWN_LENGTH_MS
        val from = start - pad
        return Slot(key, from, (end + pad).coerceAtMost(from + MAX_LENGTH_MS))
    }

    /** The scheduled recording that [slot] would overlap, or null when the connection is free then. */
    fun clash(
        scheduled: Collection<Slot>,
        slot: Slot,
    ): Slot? = scheduled.firstOrNull { it.key != slot.key && it.from < slot.until && slot.from < it.until }

    /** The tag a slot is stored under, so WorkManager keeps the schedule with no table of its own. */
    fun tag(slot: Slot): String = "$TAG${slot.from}-${slot.until}-${slot.key}"

    fun fromTag(tag: String): Slot? {
        if (!tag.startsWith(TAG)) return null
        val parts = tag.removePrefix(TAG).split('-', limit = 3)
        if (parts.size != 3) return null
        val from = parts[0].toLongOrNull() ?: return null
        val until = parts[1].toLongOrNull() ?: return null
        return Slot(parts[2], from, until)
    }

    private const val TAG = "recording-slot:"
}
