package com.tunedplayer.app.data

import com.tunedplayer.app.data.db.TitleRatingsSql
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.data.db.offMain
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ratings Top rated sorts on (Melvin, 2026-11-21: "sorting by rating ... doesn't really seem to be doing anything";
 * 2026-11-23: TMDB only, never the provider's). While Movies or Shows is sorted by rating, the titles TMDB hasn't been
 * asked about yet are looked up in the background, a few at a time, and the list re-sorts as their ratings land
 * ([TitleRatingsSql]). Each answer is kept on the device ([MetadataRepository]), so this happens once per title; it
 * asks TMDB only, never the provider, so it never takes the stream connection. [progress] says how far it has got.
 * When TMDB answers 429 it waits longer and resumes the same titles rather than giving up (Melvin, 2026-11-23).
 */
class TitleRatings(
    private val db: TunedDatabase,
    private val metadata: MetadataRepository,
) {
    /**
     * How far a lookup has got: [done] of [total] titles, for Shows when [tv]. [startedAt] is when this fill began, so
     * the screen can estimate how much longer it will take.
     */
    data class Progress(
        val tv: Boolean,
        val done: Int,
        val total: Int,
        val startedAt: Long = 0,
    )

    private val running = MutableStateFlow<Progress?>(null)

    /** The lookup under way, or null. */
    val progress: StateFlow<Progress?> = running.asStateFlow()

    private val lock = Mutex()

    /** Puts what the device knows in place, then asks TMDB about the titles it hasn't yet. Shows when [tv]. */
    suspend fun fill(tv: Boolean) = lock.withLock {
        try {
            TitleRatingsSql.applyAll(db)
            if (!metadata.hasKey()) return@withLock
            val names = unasked(tv)
            if (names.isEmpty()) return@withLock
            AppLog.i(TAG) { "Looking up ${names.size} ratings for Top rated" }
            lookUp(names, tv)
        } finally {
            running.value = null
        }
    }

    private suspend fun lookUp(
        names: List<String>,
        tv: Boolean,
    ) {
        var done = 0
        var failedInARow = 0
        var rateLimitedInARow = 0
        val startedAt = System.currentTimeMillis()
        var applied = startedAt
        running.value = Progress(tv, 0, names.size, startedAt)
        var index = 0
        while (index < names.size) {
            val end = minOf(index + AT_ONCE, names.size)
            val chunk = names.subList(index, end)
            val results =
                coroutineScope {
                    chunk
                        .map { name ->
                            async {
                                attempt(TAG, "Rating lookup failed") { metadata.ask(name, tv) }
                                    .getOrDefault(RatingAsk.FAILED)
                            }
                        }.awaitAll()
                }
            val rateLimited = results.any { it == RatingAsk.RATE_LIMITED }
            val allAnswered = results.all { it == RatingAsk.ANSWERED }
            val anyAnswered = results.any { it == RatingAsk.ANSWERED }
            when {
                allAnswered -> {
                    failedInARow = 0
                    rateLimitedInARow = 0
                    index = end
                    done = index
                    running.value = Progress(tv, done, names.size, startedAt)
                }

                rateLimited -> {
                    // TMDB said slow down: wait longer each time, and retry the same titles (cached ones skip).
                    rateLimitedInARow++
                    failedInARow = 0
                    val wait = rateLimitWaitMs(rateLimitedInARow)
                    AppLog.i(TAG) {
                        "TMDB rate-limited; waiting ${wait / 1_000}s then resume at $done of ${names.size}"
                    }
                    delay(wait)
                }

                anyAnswered -> {
                    // Some landed, some failed: short pause and retry the chunk so the rest can catch up.
                    failedInARow = 0
                    rateLimitedInARow = 0
                    delay(RETRY_CHUNK_MS)
                }

                else -> {
                    // Offline, or TMDB down without a 429: wait a little, and stop after a few tries rather than spin.
                    failedInARow++
                    rateLimitedInARow = 0
                    if (failedInARow >= GIVE_UP_AFTER) {
                        AppLog.i(TAG) { "TMDB isn't answering; ratings stop at $done of ${names.size}" }
                        break
                    }
                    delay(BACK_OFF_MS)
                }
            }
            // The list re-sorts as ratings land, every few seconds rather than with each one, so it doesn't jump.
            val now = System.currentTimeMillis()
            if (now - applied >= APPLY_EVERY_MS) {
                TitleRatingsSql.applyAll(db)
                applied = now
            }
        }
        TitleRatingsSql.applyAll(db)
    }

    /** Titles TMDB hasn't answered for on this device, in playlist order. Adult titles are left out. */
    private suspend fun unasked(tv: Boolean): List<String> = offMain {
        val query = if (tv) UNASKED_SHOWS else UNASKED_FILMS
        db.openHelper.readableDatabase.query(query).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private companion object {
        const val TAG = "TitleRatings"

        /** Lookups at once: well inside TMDB's limit of about 40 a second. */
        const val AT_ONCE = 6
        const val APPLY_EVERY_MS = 4_000L
        const val BACK_OFF_MS = 5_000L
        const val RETRY_CHUNK_MS = 1_000L
        const val GIVE_UP_AFTER = 3

        /** First wait after a 429; doubles each time up to [RATE_LIMIT_WAIT_MAX_MS]. */
        const val RATE_LIMIT_WAIT_MS = 30_000L
        const val RATE_LIMIT_WAIT_MAX_MS = 5 * 60_000L

        const val UNASKED_FILMS =
            "SELECT i.name FROM items i WHERE i.kind = 1 AND i.adult = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM metadata m WHERE m.cacheKey = 'movie|' || i.name) " +
                "GROUP BY i.name ORDER BY MIN(i.sortOrder)"
        const val UNASKED_SHOWS =
            "SELECT g.showName FROM show_groups g WHERE g.adult = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM metadata m WHERE m.cacheKey = 'tv|' || g.showName) " +
                "GROUP BY g.showName ORDER BY MIN(g.sortOrder)"

        /** 30s, 60s, 120s, 240s, then 5 min while Top rated stays open. */
        fun rateLimitWaitMs(streak: Int): Long {
            val shift = (streak - 1).coerceIn(0, 4)
            val wait = RATE_LIMIT_WAIT_MS shl shift
            return minOf(wait, RATE_LIMIT_WAIT_MAX_MS)
        }
    }
}
