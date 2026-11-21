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
 * The ratings Top rated sorts on (Melvin, 2026-11-21: "sorting by rating ... doesn't really seem to be doing anything").
 * The star on a poster is TMDB's, looked up as the poster scrolls into view, while the sort read only the provider's
 * rating, which most providers leave empty. So while Movies or Shows is sorted by rating, the titles TMDB hasn't been
 * asked about yet are looked up in the background, a few at a time, and the list re-sorts as their ratings land
 * ([TitleRatingsSql]). Each answer is kept on the device ([MetadataRepository]), so this happens once per title; it
 * asks TMDB only, never the provider, so it never takes the stream connection. [progress] says how far it has got.
 */
class TitleRatings(
    private val db: TunedDatabase,
    private val metadata: MetadataRepository,
) {
    /** How far a lookup has got: [done] of [total] titles, for Shows when [tv]. */
    data class Progress(
        val tv: Boolean,
        val done: Int,
        val total: Int,
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
        var applied = System.currentTimeMillis()
        running.value = Progress(tv, 0, names.size)
        for (chunk in names.chunked(AT_ONCE)) {
            val answered =
                coroutineScope {
                    chunk
                        .map { name ->
                            async {
                                attempt(TAG, "Rating lookup failed") { metadata.summary(name, tv) }
                                metadata.answered(name, tv)
                            }
                        }.awaitAll()
                }.count { it }
            done += chunk.size
            running.value = Progress(tv, done, names.size)
            if (answered == 0) {
                // Offline, or TMDB busy: wait a little, and stop after a few tries rather than spin.
                failedInARow++
                if (failedInARow >= GIVE_UP_AFTER) {
                    AppLog.i(TAG) { "TMDB isn't answering; ratings stop at $done of ${names.size}" }
                    break
                }
                delay(BACK_OFF_MS)
            } else {
                failedInARow = 0
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
        const val GIVE_UP_AFTER = 3

        const val UNASKED_FILMS =
            "SELECT i.name FROM items i WHERE i.kind = 1 AND i.adult = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM metadata m WHERE m.cacheKey = 'movie|' || i.name) " +
                "GROUP BY i.name ORDER BY MIN(i.sortOrder)"
        const val UNASKED_SHOWS =
            "SELECT g.showName FROM show_groups g WHERE g.adult = 0 " +
                "AND NOT EXISTS (SELECT 1 FROM metadata m WHERE m.cacheKey = 'tv|' || g.showName) " +
                "GROUP BY g.showName ORDER BY MIN(g.sortOrder)"
    }
}
