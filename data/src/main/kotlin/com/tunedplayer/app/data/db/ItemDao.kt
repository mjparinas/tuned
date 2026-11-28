package com.tunedplayer.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Every list query takes the same filters: hidden regions, whether adult categories show, an optional category and
 * an optional search. Region NULL (no country found) always shows.
 */
@Dao
interface ItemDao {
    /**
     * An update, not a REPLACE, for a stream already cached: REPLACE deletes the old row without firing delete
     * triggers, which would leave its words behind in the search index ([ItemFts]).
     */
    @Upsert
    suspend fun insertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    @Query("SELECT id FROM items")
    suspend fun allIds(): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM items)")
    suspend fun any(): Boolean

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM items")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): ItemEntity?

    /** The live channel watched last, for the quick-settings tile. */
    @Query(
        "SELECT i.* FROM history h JOIN items i ON i.id = h.itemId WHERE h.profileId = $ME AND i.kind = 0 " +
            "ORDER BY h.watchedAt DESC LIMIT 1",
    )
    suspend fun lastWatchedChannel(): ItemEntity?

    @Query("SELECT * FROM items WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<ItemEntity>

    @Query("SELECT DISTINCT tvgId FROM items WHERE kind = 0 AND tvgId IS NOT NULL")
    suspend fun liveTvgIds(): List<String>

    @Query("SELECT tvgId, url FROM items WHERE kind = 0 AND tvgId IS NOT NULL")
    suspend fun liveLinks(): List<LiveLink>

    /** Live TV in playlist order. Paged, so it reads only the rows on screen, straight down the (kind, sortOrder) index. */
    @Suppress("LongParameterList") // Every Live TV filter is a parameter of the one query, so the list stays one read.
    @Query("$LIVE_ALL $LIVE_ALL_ORDER")
    fun channels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
        topic: String,
        quick: List<String>,
        quality: String,
        genre: Int,
    ): PagingSource<Int, ChannelWithNow>

    /** Favourite channels, in their own order (Settings › Order favourites). */
    @Suppress("LongParameterList") // Every Live TV filter is a parameter of the one query, so the list stays one read.
    @Query("$LIVE_FAVOURITES $LIVE_FAVOURITES_ORDER")
    fun favouriteChannels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
        topic: String,
        quick: List<String>,
        quality: String,
        genre: Int,
    ): PagingSource<Int, ChannelWithNow>

    /** Channels watched lately, latest first. */
    @Suppress("LongParameterList") // Every Live TV filter is a parameter of the one query, so the list stays one read.
    @Query("$LIVE_RECENT $LIVE_RECENT_ORDER")
    fun recentChannels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
        topic: String,
        quick: List<String>,
        quality: String,
        genre: Int,
    ): PagingSource<Int, ChannelWithNow>

    /** Movies or episodes started and not finished, last watched first, for the Continue Watching row. */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.showName, i.season, i.episode, h.positionMs, h.durationMs, h.watchedAt
        FROM history h
        JOIN items i ON i.id = h.itemId
        WHERE h.profileId = $ME AND i.kind = :kind
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            $TAGGED_HIDDEN_I
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND h.durationMs > 0 AND h.positionMs > 30000 AND h.positionMs < h.durationMs * 95 / 100
        ORDER BY h.watchedAt DESC
        LIMIT 20
        """,
    )
    fun continueWatching(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<ContinueRow>>

    @Query("$TITLES $TITLES_PROVIDER")
    @Suppress("LongParameterList") // Every filter is a parameter of the one query, so the list stays one read.
    fun items(
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

    @Query("$SHOWS $SHOWS_PROVIDER")
    fun shows(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        favouriteShows: List<String>,
        query: String,
        match: String,
    ): PagingSource<Int, ShowSummary>

    /** Search everywhere: the first few live channels for a search (names, or what's on now and within the hour). */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.groupName, i.tvgId, i.number,
            p.title AS nowTitle, p.start AS nowStart, p.stop AS nowStop,
            (f.itemId IS NOT NULL) AS favourite
        FROM items i
        LEFT JOIN programmes p ON p.rowId = (
            SELECT rowId FROM programmes WHERE channelId = i.tvgId AND start <= :now AND stop > :now ORDER BY start DESC LIMIT 1
        )
        LEFT JOIN favourites f ON f.itemId = i.id AND f.profileId = $ME
        WHERE i.kind = 0
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            $TAGGED_HIDDEN_I
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match) OR i.tvgId IN (
                SELECT channelId FROM programmes
                WHERE start > :now - 86400000 AND start < :now + 3600000 AND stop > :now AND title LIKE '%' || :query || '%'
            ))
        ORDER BY (f.itemId IS NULL), i.sortOrder
        LIMIT :limit
        """,
    )
    suspend fun searchChannels(
        query: String,
        match: String,
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        limit: Int,
    ): List<ChannelWithNow>

    @Query(
        """
        SELECT * FROM items i
        WHERE i.kind = 1 AND i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match)
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            $TAGGED_HIDDEN_I
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        ORDER BY i.sortOrder
        LIMIT :limit
        """,
    )
    suspend fun searchMovies(
        match: String,
        hidden: List<String>,
        adult: Boolean,
        limit: Int,
    ): List<ItemEntity>

    @Query(
        """
        SELECT showKey, MIN(showName) AS showName, MAX(logo) AS logo, MIN(groupName) AS groupName, COUNT(*) AS episodes,
            SUM(CASE WHEN i.showKey IN (SELECT showKey FROM seen_episodes WHERE profileId = $ME)
                AND i.id NOT IN (SELECT itemId FROM seen_episodes WHERE profileId = $ME)
                THEN 1 ELSE 0 END) AS newEpisodes
        FROM items i
        WHERE i.kind = 2 AND i.showKey IS NOT NULL AND i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match)
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            $TAGGED_HIDDEN_I
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        GROUP BY showKey
        ORDER BY MIN(sortOrder)
        LIMIT :limit
        """,
    )
    suspend fun searchShows(
        match: String,
        hidden: List<String>,
        adult: Boolean,
        limit: Int,
    ): List<ShowSummary>

    @Query("SELECT * FROM items WHERE kind = 2 AND showKey = :showKey ORDER BY season, episode, sortOrder")
    fun episodes(showKey: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE kind = 2 AND showKey = :showKey ORDER BY season, episode, sortOrder")
    suspend fun episodesNow(showKey: String): List<ItemEntity>

    @Query(
        """
        SELECT * FROM items
        WHERE kind = 0 AND groupName = :group AND COALESCE(region, 'none') NOT IN (:hidden) AND ('g:' || kind || ':' || groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND id IN (SELECT itemId FROM channel_scan WHERE hidden = 1)) AND (:adult OR adult = 0)
            $KIDS_ONLY_TAGS
            $TAGGED_HIDDEN
        ORDER BY sortOrder
        """,
    )
    suspend fun channelsInGroup(
        group: String,
        hidden: List<String>,
        adult: Boolean,
    ): List<ItemEntity>

    @Query(GROUP_COUNTS)
    fun groups(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<GroupCount>>

    /**
     * Auto-tune's candidates: live channels whose name has every word of the search ([com.tunedplayer.app.core.Tuner.words], in
     * any order; an unused word is empty and matches anything), or whose guide has such a programme on now or starting
     * within [to]. `LIKE` ignores case for English letters, which is what event names use.
     * Channels hidden as dead or as "No event" placeholders come back marked `held`: the caller keeps the sports, PPV
     * and event ones ([EventChannels][com.tunedplayer.app.core.EventChannels]), since an empty slot can carry the next event.
     */
    @Query(
        """
        SELECT i.id, i.name, i.groupName, i.region,
            (i.searchName LIKE '%' || :w1 || '%' AND i.searchName LIKE '%' || :w2 || '%'
                AND i.searchName LIKE '%' || :w3 || '%') AS nameMatch,
            (i.tvgId IN (
                SELECT channelId FROM programmes
                WHERE start > :from - 86400000 AND start < :to AND stop > :from AND title LIKE '%' || :w1 || '%'
                    AND title LIKE '%' || :w2 || '%' AND title LIKE '%' || :w3 || '%'
            )) AS guideMatch,
            h.okAt, h.deadAt, h.height,
            (('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
                OR ('placeholder' IN (:hidden) AND (i.tags & ${ItemTags.PLACEHOLDER}) != 0)) AS held
        FROM items i
        LEFT JOIN channel_health h ON h.itemId = i.id
        WHERE i.kind = 0
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (nameMatch OR guideMatch)
        ORDER BY i.sortOrder
        LIMIT 500
        """,
    )
    suspend fun tuneCandidates(
        w1: String,
        w2: String,
        w3: String,
        from: Long,
        to: Long,
        hidden: List<String>,
        adult: Boolean,
    ): List<TuneRow>

    /**
     * Possible backup copies of a live channel: other live channels whose name has every word of the channel's
     * [ChannelCopies][com.tunedplayer.app.core.ChannelCopies] key, through the full-text index. The caller keeps only exact key
     * matches. Hidden channels are left out like everywhere else.
     */
    @Query(
        """
        SELECT i.id, i.name, i.groupName, i.region, 1 AS nameMatch, 0 AS guideMatch, h.okAt, h.deadAt, h.height, 0 AS held
        FROM items i
        LEFT JOIN channel_health h ON h.itemId = i.id
        WHERE i.kind = 0 AND i.id != :exclude
            AND i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match)
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            $TAGGED_HIDDEN_I
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        ORDER BY i.sortOrder
        LIMIT 200
        """,
    )
    suspend fun copyCandidates(
        exclude: Long,
        match: String,
        hidden: List<String>,
        adult: Boolean,
    ): List<TuneRow>

    /** Each region and its stream count; streams with no region count under [com.tunedplayer.app.core.Regions.NONE]. */
    @Query("SELECT COALESCE(region, 'none') AS region, COUNT(*) AS count FROM items GROUP BY region ORDER BY count DESC")
    fun regions(): Flow<List<RegionCount>>
}
