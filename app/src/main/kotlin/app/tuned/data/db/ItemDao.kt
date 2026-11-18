package app.tuned.data.db

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
    @Query(
        "SELECT $CHANNEL_COLUMNS, 0 AS favourite FROM items i $NOW_JOIN WHERE $CHANNEL_FILTER ORDER BY i.sortOrder",
    )
    fun channels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
    ): PagingSource<Int, ChannelWithNow>

    /** Favourite channels, in their own order (Settings › Order favourites). */
    @Query(
        "SELECT $CHANNEL_COLUMNS, 1 AS favourite FROM items i JOIN favourites f ON f.itemId = i.id AND f.profileId = $ME $NOW_JOIN " +
            "WHERE $CHANNEL_FILTER ORDER BY f.position",
    )
    fun favouriteChannels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
    ): PagingSource<Int, ChannelWithNow>

    /** Channels watched lately, latest first. */
    @Query(
        "SELECT $CHANNEL_COLUMNS, 0 AS favourite FROM items i JOIN history h ON h.itemId = i.id AND h.profileId = $ME $NOW_JOIN " +
            "WHERE $CHANNEL_FILTER ORDER BY h.watchedAt DESC",
    )
    fun recentChannels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        query: String,
        match: String,
    ): PagingSource<Int, ChannelWithNow>

    /** Movies or episodes started and not finished, last watched first, for the Continue Watching row. */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.showName, i.season, i.episode, h.positionMs, h.durationMs
        FROM history h
        JOIN items i ON i.id = h.itemId
        WHERE h.profileId = $ME AND i.kind = :kind
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
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

    @Query(
        """
        SELECT * FROM items i
        WHERE i.kind = :kind
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites WHERE profileId = $ME))
            AND (:query = '' OR i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match))
        ORDER BY i.sortOrder
        """,
    )
    fun items(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
        match: String,
    ): PagingSource<Int, ItemEntity>

    @Query(
        """
        SELECT showKey, MIN(showName) AS showName, MAX(logo) AS logo, MIN(groupName) AS groupName, COUNT(*) AS episodes,
            SUM(CASE WHEN i.showKey IN (SELECT showKey FROM seen_episodes WHERE profileId = $ME)
                AND i.id NOT IN (SELECT itemId FROM seen_episodes WHERE profileId = $ME)
                THEN 1 ELSE 0 END) AS newEpisodes
        FROM items i
        WHERE i.kind = 2 AND i.showKey IS NOT NULL
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites WHERE profileId = $ME))
            AND (:query = '' OR i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match))
        GROUP BY showKey
        ORDER BY MIN(sortOrder)
        """,
    )
    fun shows(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
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
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
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
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
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
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
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
            $KIDS_ONLY
            AND NOT ('placeholder' IN (:hidden) AND kind = 0
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
        ORDER BY sortOrder
        """,
    )
    suspend fun channelsInGroup(
        group: String,
        hidden: List<String>,
        adult: Boolean,
    ): List<ItemEntity>

    @Query(
        """
        SELECT groupName, COUNT(*) AS count FROM items
        WHERE kind = :kind AND COALESCE(region, 'none') NOT IN (:hidden) AND ('g:' || kind || ':' || groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND id IN (SELECT itemId FROM channel_scan WHERE hidden = 1)) AND (:adult OR adult = 0)
            $KIDS_ONLY
            AND NOT ('placeholder' IN (:hidden) AND kind = 0
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
        GROUP BY groupName ORDER BY MIN(sortOrder)
        """,
    )
    fun groups(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<GroupCount>>

    /**
     * Auto-tune's candidates: live channels whose name has every word of the search ([app.tuned.core.Tuner.words], in
     * any order; an unused word is empty and matches anything), or whose guide has such a programme on now or starting
     * within [to]. `LIKE` ignores case for English letters, which is what event names use.
     * Channels hidden as dead or as "No event" placeholders come back marked `held`: the caller keeps the sports, PPV
     * and event ones ([EventChannels][app.tuned.core.EventChannels]), since an empty slot can carry the next event.
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
                OR ('placeholder' IN (:hidden)
                    AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))) AS held
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
     * [ChannelCopies][app.tuned.core.ChannelCopies] key, through the full-text index. The caller keeps only exact key
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
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden)
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
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

    /**
     * Live Events: what's on sports and pay-per-view channels from now until [to], for
     * [Events][app.tuned.core.Events] to sort into events. The category words here are a quick first cut; the rules
     * in `Events` decide. Hidden countries and groups are left out; "No event" placeholders and channels a scan
     * found dead are kept, since the guide says an event is on them.
     */
    @Query(
        """
        SELECT i.id AS itemId, i.name AS channelName, i.groupName AS `group`, p.title, p.start, p.stop
        FROM programmes p
        JOIN items i ON i.tvgId = p.channelId AND i.kind = 0
        WHERE p.stop > :from AND p.start < :to
            AND (
                (lower(i.groupName) || ' ' || i.searchName) GLOB '*sport*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*ppv*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*event*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*ufc*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*espn*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*dazn*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*tsn*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*bein*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*nfl*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*nba*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*nhl*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*mlb*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*f1*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*fight*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*boxing*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*wwe*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*golf*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*tennis*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*cricket*'
                OR (lower(i.groupName) || ' ' || i.searchName) GLOB '*rugby*'
            )
            AND COALESCE(i.region, 'none') NOT IN (:hidden) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        ORDER BY p.start, i.sortOrder
        LIMIT 20000
        """,
    )
    suspend fun eventProgrammes(
        from: Long,
        to: Long,
        hidden: List<String>,
        adult: Boolean,
    ): List<EventRow>

    /** Each region and its stream count; streams with no region count under [app.tuned.core.Regions.NONE]. */
    @Query("SELECT COALESCE(region, 'none') AS region, COUNT(*) AS count FROM items GROUP BY region ORDER BY count DESC")
    fun regions(): Flow<List<RegionCount>>
}
