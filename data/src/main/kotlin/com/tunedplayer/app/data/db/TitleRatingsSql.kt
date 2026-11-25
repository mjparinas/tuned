package com.tunedplayer.app.data.db

import androidx.room.withTransaction

/**
 * Top rated sorts on TMDB's rating only (Melvin, 2026-11-23: "i dont care about the provider provided information, i
 * only care about tmdb"): kept in [MetadataEntity] under the title's own name; none (and so last) until TMDB has found
 * the title, or when too few have voted. Films keep it in [ItemEntity.topRating], indexed, so the list still reads
 * straight down an index a page at a time; shows in [ShowGroupEntity.rating], which [ShowGroups] rebuilds after each
 * refresh. Only rows whose value changes are written. A rating of 0 counts as none (same as
 * [com.tunedplayer.app.core.TitleSort.rating]), so unrated titles stay at the bottom when Top rated sorts highest first.
 */
object TitleRatingsSql {
    /**
     * A film's TMDB rating once TMDB has found it (none when too few have voted, or TMDB hasn't answered), for a row
     * of `items`. Zero is stored as null so DESC puts it with the unrated.
     */
    private const val FILM_RATING =
        "NULLIF((SELECT MAX(m.rating) FROM metadata m WHERE m.cacheKey = 'movie|' || items.name AND m.found = 1), 0)"

    /** The same for a show's card, for a row of `show_groups`. */
    private const val SHOW_RATING =
        "NULLIF((SELECT MAX(m.rating) FROM metadata m WHERE m.cacheKey = 'tv|' || show_groups.showName AND m.found = 1), 0)"

    const val FILMS = "UPDATE items SET topRating = $FILM_RATING WHERE kind = 1 AND topRating IS NOT $FILM_RATING"

    const val SHOWS = "UPDATE show_groups SET rating = $SHOW_RATING WHERE rating IS NOT $SHOW_RATING"

    /** Puts every rating the device knows in place, for films and shows. */
    suspend fun applyAll(db: TunedDatabase) = offMain {
        // Room's transaction so the Movies and Shows paging sources see the new order (raw execSQL alone can miss it).
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL(FILMS)
            sql.execSQL(SHOWS)
        }
    }

    /** Films only: after a refresh, whose rewritten rows start without one. */
    suspend fun applyFilms(db: TunedDatabase) = offMain {
        db.withTransaction { db.openHelper.writableDatabase.execSQL(FILMS) }
    }
}
