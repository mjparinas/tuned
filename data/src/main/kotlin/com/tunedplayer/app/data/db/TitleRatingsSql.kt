package com.tunedplayer.app.data.db

import androidx.room.withTransaction

/**
 * Top rated sorts on the rating a poster shows (Melvin, 2026-11-21: "it's not sorting based on rating"): TMDB's, kept
 * in [MetadataEntity] under the title's own name, and the provider's only where TMDB hasn't found the title. Films keep it in
 * [ItemEntity.topRating], indexed, so the list still reads straight down an index a page at a time; shows in
 * [ShowGroupEntity.rating], which [ShowGroups] rebuilds after each refresh. Only rows whose value changes are written.
 * A rating of 0 counts as none (same as [com.tunedplayer.app.core.TitleSort.rating]), so unrated titles stay at the
 * bottom when Top rated sorts highest first (Melvin, 2026-11-23).
 */
object TitleRatingsSql {
    /**
     * The rating a film's poster shows: TMDB's once TMDB has found the film (none when too few have voted), else the
     * provider's, for a row of `items`. Zero is stored as null so DESC puts it with the unrated.
     */
    private const val FILM_RATING =
        "NULLIF((SELECT CASE WHEN COUNT(*) > 0 THEN MAX(m.rating) ELSE items.rating END FROM metadata m " +
            "WHERE m.cacheKey = 'movie|' || items.name AND m.found = 1), 0)"

    /** The same for a show's card, for a row of `show_groups`. */
    private const val SHOW_RATING =
        "NULLIF((SELECT CASE WHEN COUNT(*) > 0 THEN MAX(m.rating) ELSE show_groups.rating END FROM metadata m " +
            "WHERE m.cacheKey = 'tv|' || show_groups.showName AND m.found = 1), 0)"

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
