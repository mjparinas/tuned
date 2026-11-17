package app.tuned.core

/**
 * Catch-up (Xtream `tv_archive`): a channel that keeps recordings for a few days can replay any programme that has
 * started within them, from the beginning.
 */
object CatchUp {
    /** No provider keeps more than a week worth having in the guide; past programmes beyond it aren't stored. */
    const val MAX_DAYS = 7
    private const val DAY = 24 * 3_600_000L
    private const val MINUTE = 60_000L

    private val trailingId = Regex("""/(\d+)(\.[A-Za-z0-9]+)?$""")

    /** The Xtream stream id at the end of a live link (`…/live/user/pass/123.ts` or `…/user/pass/123`). */
    fun streamId(url: String): String? = trailingId.find(url.substringBefore('?'))?.groupValues?.get(1)

    /** Days of recordings from a `get_live_streams` entry; servers send the values as numbers or strings. */
    fun days(
        tvArchive: String?,
        duration: String?,
    ): Int = if (tvArchive?.trim() == "1") (duration?.trim()?.toIntOrNull() ?: 0).coerceIn(0, MAX_DAYS) else 0

    /** Whether a programme starting at [start] can be replayed now, on a channel that keeps [days] of recordings. */
    fun available(
        start: Long,
        now: Long,
        days: Int,
    ): Boolean = days > 0 && start < now && start >= now - days * DAY

    /** The oldest start time still worth keeping in the guide for a channel that keeps [days] of recordings. */
    fun keepFrom(
        now: Long,
        days: Int,
    ): Long = now - days.coerceIn(0, MAX_DAYS) * DAY

    /** Whole minutes covering a programme, as the timeshift link wants them. */
    fun minutes(
        start: Long,
        stop: Long,
    ): Int = ((stop - start + MINUTE - 1) / MINUTE).toInt().coerceAtLeast(1)
}
