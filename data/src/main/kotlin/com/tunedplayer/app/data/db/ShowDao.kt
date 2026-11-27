package com.tunedplayer.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** The Shows tab's per-show table ([ShowGroupEntity]). */
@Dao
interface ShowDao {
    /**
     * The Shows tab when nothing narrows it: grouped from [ShowGroupEntity], a few rows per show, instead of from
     * every episode. New episodes are counted only for the shows on screen.
     */
    @Query("$SHOWS_TAB ORDER BY MIN(s.sortOrder)")
    fun showsTab(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
    ): PagingSource<Int, ShowSummary>

    /** [showsTab], newest first: the newest episode or the provider's last change. */
    @Query("$SHOWS_TAB ORDER BY MAX(s.added) DESC, MIN(s.sortOrder)")
    fun showsTabByAdded(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
    ): PagingSource<Int, ShowSummary>

    /** [showsTab], newest release year first, shows without one last. */
    @Query("$SHOWS_TAB ORDER BY MAX(s.year) DESC, MIN(s.sortOrder)")
    fun showsTabByYear(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
    ): PagingSource<Int, ShowSummary>

    /**
     * [showsTab], highest rated first, shows without a rating last (Melvin, 2026-11-23: "without a rating need to go
     * all the way to the bottom" / "highest scores first").
     */
    @Query("$SHOWS_TAB ORDER BY (MAX(s.rating) IS NULL), MAX(s.rating) DESC, MIN(s.sortOrder)")
    fun showsTabByRating(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
    ): PagingSource<Int, ShowSummary>

    /** [showsTab], A to Z by title without tags. */
    @Query("$SHOWS_TAB ORDER BY MIN(NULLIF(s.sortTitle, '')), MIN(s.sortOrder)")
    fun showsTabByTitle(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
    ): PagingSource<Int, ShowSummary>

    @Query("DELETE FROM show_groups")
    suspend fun deleteShowGroups()

    /** Every show's key, to find the shows favourited whole ([com.tunedplayer.app.data.FavouriteShows]). */
    @Query("SELECT DISTINCT showKey FROM show_groups")
    suspend fun showKeys(): List<String>

    /** Opened Xtream shows' own covers ([ShowCoverEntity]). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCovers(covers: List<ShowCoverEntity>)

    @Query("DELETE FROM show_covers")
    suspend fun deleteCovers()

    /** The shows' year, rating and last change from the Xtream API ([ShowDetailsEntity]), all at once. */
    @Transaction
    suspend fun replaceDetails(details: List<ShowDetailsEntity>) {
        deleteDetails()
        details.chunked(DETAILS_BATCH).forEach { insertDetails(it) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDetails(details: List<ShowDetailsEntity>)

    @Query("DELETE FROM show_details")
    suspend fun deleteDetails()

    /** [ItemDao.groups] for Shows, from the per-show table: the same counts without reading every episode. */
    @Query(
        """
        SELECT groupName, SUM(episodes) AS count FROM show_groups
        WHERE COALESCE(region, 'none') NOT IN (:hidden) AND ('g:2:' || groupName) NOT IN (:hidden) AND (:adult OR adult = 0)
            $KIDS_ONLY_GROUPS
            $ROUND_THE_CLOCK_GROUPS
        GROUP BY groupName ORDER BY MIN(sortOrder)
        """,
    )
    fun showGroupChips(
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<GroupCount>>

    /**
     * Episodes already fetched for shows read from the Xtream API (`core/XtreamApi`) or a portal (`core/Stalker`):
     * every episode with a real link.
     * A refresh keeps them in place of the show's stand-in row, so an opened show doesn't fold back to one row.
     */
    @Query(
        "SELECT * FROM items WHERE kind = 2 AND url NOT LIKE 'xtream-series:%' AND url NOT LIKE 'stalker-show:%' " +
            "AND url NOT LIKE 'plex-show:%' AND url NOT LIKE 'jellyfin-show:%' AND url NOT LIKE 'emby-show:%'",
    )
    suspend fun fetchedEpisodes(): List<ItemEntity>

    /** Swaps a show's stand-in row for its episodes, in one go so the show page never sees neither. */
    @Transaction
    suspend fun replaceStandIn(
        standInId: Long,
        episodes: List<ItemEntity>,
    ) {
        insertEpisodes(episodes)
        deleteItem(standInId)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItem(id: Long)
}

private const val DETAILS_BATCH = 500

/**
 * The Shows tab when nothing narrows it: grouped from [ShowGroupEntity], a few rows per show, instead of from every
 * episode. New episodes are counted only for the shows on screen. Each sort adds its own ORDER BY ([ShowDao.showsTab]).
 */
private const val SHOWS_TAB = """
        SELECT s.showKey, MIN(s.showName) AS showName, MAX(s.logo) AS logo, MIN(s.groupName) AS groupName,
            SUM(s.episodes) AS episodes,
            CASE WHEN s.showKey IN (SELECT showKey FROM seen_episodes WHERE profileId = $ME) THEN (
                SELECT COUNT(*) FROM items e
                WHERE e.kind = 2 AND e.showKey = s.showKey AND e.id NOT IN (SELECT itemId FROM seen_episodes WHERE profileId = $ME)
            ) ELSE 0 END AS newEpisodes
        FROM show_groups s
        WHERE COALESCE(s.region, 'none') NOT IN (:hidden) AND ('g:2:' || s.groupName) NOT IN (:hidden)
            AND (:adult OR s.adult = 0)
            $KIDS_ONLY_S
            $ROUND_THE_CLOCK_S
            AND (:group IS NULL OR s.groupName = :group)
        GROUP BY s.showKey
"""
