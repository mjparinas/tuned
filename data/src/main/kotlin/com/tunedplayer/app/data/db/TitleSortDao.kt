package com.tunedplayer.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query

/**
 * Movies and Shows in each order but the playlist's ([com.tunedplayer.app.core.TitleSort]), with the same filters as
 * [ItemDao.items] and [ItemDao.shows]. Read through [sortedItems] and [sortedShows].
 */
@Dao
interface TitleSortDao {
    /** [ItemDao.items], newest additions first: read down its own index a page at a time ([ItemEntity]). */
    @Query("SELECT * FROM items i $TITLE_FILTER ORDER BY i.added DESC, i.sortOrder")
    @Suppress("LongParameterList") // the filters of [ItemDao.items]
    fun itemsByAdded(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
        /** One of the person's own lists ([ListEntity]); 0 for none. */
        listId: Long,
    ): PagingSource<Int, ItemEntity>

    /** [ItemDao.items], newest release year first, titles without one last: read down its own index a page at a time ([ItemEntity]). */
    @Query("SELECT * FROM items i $TITLE_FILTER ORDER BY i.year DESC, i.sortOrder")
    @Suppress("LongParameterList") // the filters of [ItemDao.items]
    fun itemsByYear(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
        /** One of the person's own lists ([ListEntity]); 0 for none. */
        listId: Long,
    ): PagingSource<Int, ItemEntity>

    /**
     * [ItemDao.items], highest rated first by TMDB's rating ([TitleRatingsSql]), titles without one last
     * (Melvin, 2026-11-23: "without a rating need to go all the way to the bottom" / "highest scores first"):
     * read down its own index a page at a time ([ItemEntity]). DESC already puts nulls last in SQLite.
     */
    @Query("SELECT * FROM items i $TITLE_FILTER ORDER BY i.topRating DESC, i.sortOrder")
    @Suppress("LongParameterList") // the filters of [ItemDao.items]
    fun itemsByRating(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
        /** One of the person's own lists ([ListEntity]); 0 for none. */
        listId: Long,
    ): PagingSource<Int, ItemEntity>

    /** [ItemDao.items], A to Z by title without tags: read down its own index a page at a time ([ItemEntity]). */
    @Query("SELECT * FROM items i $TITLE_FILTER ORDER BY i.sortTitle, i.sortOrder")
    @Suppress("LongParameterList") // the filters of [ItemDao.items]
    fun itemsByTitle(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
        /** One of the person's own lists ([ListEntity]); 0 for none. */
        listId: Long,
    ): PagingSource<Int, ItemEntity>

    /** [ItemDao.shows], newest first, by the show's details in [ShowGroupEntity]. */
    @Query("$SHOW_SUMMARY $SHOW_FILTER ORDER BY $SHOW_ADDED DESC, MIN(sortOrder)")
    fun showsByAdded(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
    ): PagingSource<Int, ShowSummary>

    /** [ItemDao.shows], newest year first, by the show's details in [ShowGroupEntity]. */
    @Query("$SHOW_SUMMARY $SHOW_FILTER ORDER BY $SHOW_YEAR DESC, MIN(sortOrder)")
    fun showsByYear(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
    ): PagingSource<Int, ShowSummary>

    /**
     * [ItemDao.shows], highest rated first, shows without a rating last (Melvin, 2026-11-23): by the show's details in
     * [ShowGroupEntity]. The null check is explicit because this order is computed, not an index scan.
     */
    @Query("$SHOW_SUMMARY $SHOW_FILTER ORDER BY ($SHOW_RATING IS NULL), $SHOW_RATING DESC, MIN(sortOrder)")
    fun showsByRating(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
    ): PagingSource<Int, ShowSummary>

    /** [ItemDao.shows], A to Z, by the show's details in [ShowGroupEntity]. */
    @Query("$SHOW_SUMMARY $SHOW_FILTER ORDER BY MIN(NULLIF(i.sortTitle, '')), MIN(sortOrder)")
    fun showsByTitle(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
    ): PagingSource<Int, ShowSummary>
}
