package app.tuned.core

/**
 * When a film's or show's TMDB answer comes from the device and when it's fetched (Melvin, 2026-11-17: "grab from
 * the cache first, and if it's not available, that's when you actually go to TMDB"). What the device has is always
 * shown first; an old answer is refreshed behind it rather than making the page wait, and nothing is asked offline.
 */
object MetadataCache {
    /** A found answer is kept fresh for a month, "nothing found" for a week (the film may be added to TMDB). */
    const val KEEP_MS = 30L * 24 * 3_600_000
    const val KEEP_MISSING_MS = 7L * 24 * 3_600_000

    /** What the device holds: whether TMDB matched it, whether the details page's extras are in, and when. */
    data class Row(
        val found: Boolean,
        val full: Boolean,
        val fetchedAt: Long,
    )

    enum class Use {
        /** The device's answer (or nothing), no network. */
        CACHED,

        /** The device's answer now, and a fetch behind it to bring it up to date. */
        CACHED_THEN_REFRESH,

        /** Nothing usable on the device: ask TMDB and wait. */
        FETCH,
    }

    fun decide(
        row: Row?,
        wantFull: Boolean,
        now: Long,
        online: Boolean,
    ): Use {
        val missing = row == null || (wantFull && row.found && !row.full)
        val stale = row != null && now - row.fetchedAt >= if (row.found) KEEP_MS else KEEP_MISSING_MS
        return when {
            !online -> Use.CACHED
            missing -> Use.FETCH
            stale -> Use.CACHED_THEN_REFRESH
            else -> Use.CACHED
        }
    }
}
