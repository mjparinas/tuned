package com.tunedplayer.app.core

/**
 * The grid guide's timeline: channels down the side, time across the top. Everything here is in epoch milliseconds,
 * so the screen only turns times into pixels.
 */
object GuideGrid {
    const val MINUTE = 60_000L
    const val SLOT = 30 * MINUTE
    const val HOUR = 60 * MINUTE

    /** Programmes are read and cached per channel in blocks this long, so scrolling reads each block once. */
    const val BLOCK = 6 * HOUR

    /** The half hour [time] falls in, where the timeline's ticks are. */
    fun slotStart(time: Long): Long = time.floorDiv(SLOT) * SLOT

    /** Half-hour ticks from the one at or before [from] up to [to]. */
    fun ticks(
        from: Long,
        to: Long,
    ): List<Long> {
        if (to < from) return emptyList()
        return generateSequence(slotStart(from)) { it + SLOT }.takeWhile { it <= to }.toList()
    }

    /** The cache blocks that cover [from] to [to]. */
    fun blocks(
        from: Long,
        to: Long,
    ): LongRange = from.floorDiv(BLOCK)..maxOf(from, to - 1).floorDiv(BLOCK)

    /**
     * How far back the grid scrolls: an hour (the guide keeps no more), or as many days as the longest catch-up a
     * channel offers, so those recordings can be found.
     */
    fun earliest(
        now: Long,
        catchUpDays: Int,
    ): Long = slotStart(now - maxOf(HOUR, catchUpDays.coerceIn(0, CatchUp.MAX_DAYS) * 24 * HOUR))

    /** How far ahead it scrolls: as many days as the guide keeps. */
    fun latest(
        now: Long,
        guideDays: Int,
    ): Long = slotStart(now) + maxOf(1, guideDays) * 24 * HOUR

    /** Where a scroll may start, so the visible span never runs past either end. */
    fun clampStart(
        start: Long,
        earliest: Long,
        latest: Long,
        visible: Long,
    ): Long = start.coerceIn(earliest, maxOf(earliest, latest - visible))

    /** The programmes of [all] that are on at any point between [from] and [to], in order, each once. */
    fun <T> overlapping(
        all: List<T>,
        from: Long,
        to: Long,
        start: (T) -> Long,
        stop: (T) -> Long,
    ): List<T> = all.filter { stop(it) > from && start(it) < to }.sortedBy(start)

    /**
     * The start of the view that brings a programme into sight, or [viewStart] if it already overlaps the view. A
     * remote moving onto a programme off the right edge scrolls just far enough to show its start; one wholly past
     * the left edge scrolls to its start. A show that started earlier but is still on leaves the timeline alone —
     * its title stays sticky at the left — so scrolling channels with the remote does not drag time left.
     */
    fun reveal(
        start: Long,
        stop: Long,
        viewStart: Long,
        visible: Long,
    ): Long = when {
        start >= viewStart + visible - SLOT -> slotStart(start) - visible / 2
        stop > viewStart && start < viewStart + visible -> viewStart
        start < viewStart -> slotStart(start)
        else -> viewStart
    }
}
