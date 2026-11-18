package app.tuned.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    @Query("SELECT i.* FROM history h JOIN items i ON i.id = h.itemId WHERE i.kind = 0 ORDER BY h.watchedAt DESC LIMIT 1")
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
        "SELECT $CHANNEL_COLUMNS, 1 AS favourite FROM items i JOIN favourites f ON f.itemId = i.id $NOW_JOIN " +
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
        "SELECT $CHANNEL_COLUMNS, 0 AS favourite FROM items i JOIN history h ON h.itemId = i.id $NOW_JOIN " +
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
        WHERE i.kind = :kind
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND i.kind = 0
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
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
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND i.kind = 0
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites))
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
            SUM(CASE WHEN i.showKey IN (SELECT showKey FROM seen_episodes) AND i.id NOT IN (SELECT itemId FROM seen_episodes)
                THEN 1 ELSE 0 END) AS newEpisodes
        FROM items i
        WHERE i.kind = 2 AND i.showKey IS NOT NULL
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND i.kind = 0
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites))
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
        SELECT i.id, i.name, i.logo, i.groupName, i.tvgId,
            p.title AS nowTitle, p.start AS nowStart, p.stop AS nowStop,
            (f.itemId IS NOT NULL) AS favourite
        FROM items i
        LEFT JOIN programmes p ON p.rowId = (
            SELECT rowId FROM programmes WHERE channelId = i.tvgId AND start <= :now AND stop > :now ORDER BY start DESC LIMIT 1
        )
        LEFT JOIN favourites f ON f.itemId = i.id
        WHERE i.kind = 0
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND i.kind = 0
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
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
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND i.kind = 0
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
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
            SUM(CASE WHEN i.showKey IN (SELECT showKey FROM seen_episodes) AND i.id NOT IN (SELECT itemId FROM seen_episodes)
                THEN 1 ELSE 0 END) AS newEpisodes
        FROM items i
        WHERE i.kind = 2 AND i.showKey IS NOT NULL AND i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match)
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND i.kind = 0
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
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
        WHERE kind = 0 AND groupName = :group AND (region IS NULL OR region NOT IN (:hidden)) AND ('g:' || kind || ':' || groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND id IN (SELECT itemId FROM channel_scan WHERE hidden = 1)) AND (:adult OR adult = 0)
            AND NOT ('placeholder' IN (:hidden) AND kind = 0
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND kind = 0
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
        WHERE kind = :kind AND (region IS NULL OR region NOT IN (:hidden)) AND ('g:' || kind || ':' || groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND id IN (SELECT itemId FROM channel_scan WHERE hidden = 1)) AND (:adult OR adult = 0)
            AND NOT ('placeholder' IN (:hidden) AND kind = 0
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND kind = 0
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
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND (:adult OR i.adult = 0)
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
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND i.kind = 0
                AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR i.adult = 0)
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
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND (:adult OR i.adult = 0)
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

    @Query("SELECT region, COUNT(*) AS count FROM items WHERE region IS NOT NULL GROUP BY region ORDER BY count DESC")
    fun regions(): Flow<List<RegionCount>>
}

@Dao
interface ProgrammeDao {
    @Insert
    suspend fun insertAll(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes")
    suspend fun deleteAll()

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteEndedBefore(before: Long)

    @Query("SELECT * FROM programmes WHERE channelId = :channelId AND stop > :from ORDER BY start LIMIT :limit")
    fun upcoming(
        channelId: String,
        from: Long,
        limit: Int,
    ): Flow<List<ProgrammeEntity>>

    @Query("SELECT * FROM programmes WHERE channelId = :channelId AND stop > :now ORDER BY start LIMIT 2")
    suspend fun nowAndNext(
        channelId: String,
        now: Long,
    ): List<ProgrammeEntity>

    /** A channel's programmes on at any point from [from] to [to], for one block of the grid guide. */
    @Query("SELECT * FROM programmes WHERE channelId = :channelId AND start < :to AND stop > :from ORDER BY start")
    suspend fun between(
        channelId: String,
        from: Long,
        to: Long,
    ): List<ProgrammeEntity>

    /** What's on now on each of [channelIds], for the player's mini guide. */
    @Query("SELECT * FROM programmes WHERE channelId IN (:channelIds) AND start <= :now AND stop > :now")
    suspend fun onNow(
        channelIds: List<String>,
        now: Long,
    ): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes")
    suspend fun count(): Int
}

@Dao
interface LibraryDao {
    @Upsert
    suspend fun addFavourite(favourite: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE itemId = :itemId")
    suspend fun removeFavourite(itemId: Long)

    @Query("SELECT * FROM favourites")
    suspend fun allFavourites(): List<FavouriteEntity>

    @Query("SELECT itemId FROM favourites")
    fun favouriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE itemId = :itemId)")
    fun isFavourite(itemId: Long): Flow<Boolean>

    /** Favourite channels in the order they're shown, for Settings › Order favourites. */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.tvgId FROM favourites f JOIN items i ON i.id = f.itemId
        WHERE i.kind = 0
        GROUP BY i.id
        ORDER BY f.position, i.sortOrder
        """,
    )
    fun favouriteChannels(): Flow<List<FavouriteRow>>

    @Query("UPDATE favourites SET position = :position WHERE itemId = :itemId")
    suspend fun setFavouritePosition(
        itemId: Long,
        position: Long,
    )

    /** Saves a new order: [ids] first to last. */
    @Transaction
    suspend fun reorderFavourites(ids: List<Long>) {
        ids.forEachIndexed { i, id -> setFavouritePosition(id, i.toLong()) }
    }

    @Upsert
    suspend fun saveProgress(history: HistoryEntity)

    @Query("SELECT * FROM history WHERE itemId = :itemId")
    suspend fun progress(itemId: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE itemId IN (:ids)")
    fun progressFor(ids: List<Long>): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    /** Films and episodes counted as watched: played to the end, or marked by hand. */
    @Query("SELECT itemId FROM history WHERE durationMs > 0 AND positionMs >= durationMs * 92 / 100")
    fun watchedIds(): Flow<List<Long>>

    /** Marks [ids] watched without playing them: a finished entry, so they leave Continue Watching too. */
    @Transaction
    suspend fun markWatched(ids: List<Long>) {
        val now = System.currentTimeMillis()
        for (id in ids) {
            val length = progress(id)?.durationMs?.takeIf { it > 0 } ?: 1
            saveProgress(HistoryEntity(id, positionMs = length, durationMs = length, watchedAt = now))
        }
    }

    /** Back to unwatched: forgets where they got to. */
    @Query("DELETE FROM history WHERE itemId IN (:ids)")
    suspend fun markUnwatched(ids: List<Long>)

    @Query("DELETE FROM favourites")
    suspend fun clearFavourites()

    @Query("SELECT * FROM channel_health WHERE itemId = :itemId")
    suspend fun health(itemId: Long): ChannelHealthEntity?

    @Upsert
    suspend fun saveHealth(health: ChannelHealthEntity)
}

@Dao
interface CatchUpDao {
    @Query("DELETE FROM catchup")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CatchUpEntity>)

    @Query("SELECT * FROM catchup")
    suspend fun all(): List<CatchUpEntity>

    /** The longest catch-up any channel offers, so the grid guide scrolls back that far. */
    @Query("SELECT MAX(days) FROM catchup")
    suspend fun maxDays(): Int?

    @Query("SELECT days FROM catchup WHERE streamId = :streamId")
    fun days(streamId: String): Flow<Int?>

    // What the Xtream API says about live channels, beside their catch-up days: both come from get_live_streams.
    @Query("SELECT id, url FROM items WHERE kind = 0")
    suspend fun liveUrls(): List<ItemUrl>

    /** Marks live channels the server itself calls adult (Xtream `is_adult`), whatever their category says. */
    @Query("UPDATE items SET adult = 1 WHERE id IN (:ids)")
    suspend fun markAdult(ids: List<Long>)
}

@Dao
interface DownloadDao {
    @Upsert
    suspend fun upsert(row: DownloadEntity)

    @Query("DELETE FROM downloads WHERE itemId = :itemId")
    suspend fun delete(itemId: Long)

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun get(itemId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE itemId IN (:ids) AND state = 1")
    suspend fun done(ids: List<Long>): List<DownloadEntity>

    @Query("SELECT * FROM downloads ORDER BY addedAt DESC")
    fun all(): Flow<List<DownloadEntity>>

    /** Everything saved or on its way, newest first, with each item's artwork and show while it's still in the playlist. */
    @Query(
        "SELECT d.*, i.logo AS logo, i.showKey AS showKey, i.showName AS showName, i.season AS season, i.episode AS episode, " +
            "(SELECT g.logo FROM show_groups g WHERE g.showKey = i.showKey AND g.logo IS NOT NULL LIMIT 1) AS showLogo " +
            "FROM downloads d LEFT JOIN items i ON i.id = d.itemId ORDER BY d.addedAt DESC",
    )
    fun rows(): Flow<List<DownloadRow>>

    @Query("SELECT * FROM downloads WHERE state = 0 ORDER BY addedAt LIMIT 1")
    suspend fun next(): DownloadEntity?

    @Query("UPDATE downloads SET bytes = :bytes, total = :total WHERE itemId = :itemId")
    suspend fun progress(
        itemId: Long,
        bytes: Long,
        total: Long,
    )

    @Query("UPDATE downloads SET state = :state WHERE itemId = :itemId")
    suspend fun state(
        itemId: Long,
        state: Int,
    )
}

@Dao
interface MetadataDao {
    @Upsert
    suspend fun upsert(row: MetadataEntity)

    @Query("SELECT * FROM metadata WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): MetadataEntity?

    @Upsert
    suspend fun upsertSeason(row: TmdbSeasonEntity)

    @Query("SELECT * FROM tmdb_seasons WHERE cacheKey = :cacheKey")
    suspend fun season(cacheKey: String): TmdbSeasonEntity?
}

@Dao
interface WatchLaterDao {
    @Upsert
    suspend fun add(row: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun all(): Flow<List<WatchLaterEntity>>

    @Query("SELECT `key` FROM watch_later")
    fun keys(): Flow<List<String>>
}

@Dao
interface SeenEpisodeDao {
    @Query("SELECT itemId FROM seen_episodes WHERE showKey = :showKey")
    suspend fun seen(showKey: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(rows: List<SeenEpisodeEntity>)
}

@Dao
interface ChannelScanDao {
    /** The live channels a scan looks at: those not hidden by country or group, in list order. */
    @Query(
        """
        SELECT id FROM items
        WHERE kind = 0 AND (region IS NULL OR region NOT IN (:hidden)) AND ('g:' || kind || ':' || groupName) NOT IN (:hidden)
            AND NOT ('placeholder' IN (:hidden)
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
            AND NOT ('247' IN (:hidden) AND kind = 0
                AND ((' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]247[^0-9]*'
                    OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*'))
            AND (:adult OR adult = 0)
        ORDER BY sortOrder
        """,
    )
    suspend fun channels(
        hidden: List<String>,
        adult: Boolean,
    ): List<Long>

    /** The channel that played most recently, in a scan or in the player, to compare failures against. */
    @Query(
        """
        SELECT itemId FROM (
            SELECT itemId, lastOkAt AS at FROM channel_scan WHERE lastOkAt > 0
            UNION ALL SELECT itemId, okAt AS at FROM channel_health WHERE okAt > 0
        ) WHERE itemId IN (SELECT id FROM items WHERE kind = 0) ORDER BY at DESC LIMIT 1
        """,
    )
    suspend fun knownGood(): Long?

    @Query("SELECT * FROM channel_scan WHERE itemId = :itemId")
    suspend fun get(itemId: Long): ChannelScanEntity?

    @Upsert
    suspend fun save(row: ChannelScanEntity)

    @Query("DELETE FROM channel_scan")
    suspend fun deleteAll()

    /** Hidden channels with their names, for [app.tuned.data.ChannelScanner.releaseEventChannels]. */
    @Query("SELECT s.itemId, i.name, i.groupName FROM channel_scan s JOIN items i ON i.id = s.itemId WHERE s.hidden = 1")
    suspend fun hiddenNames(): List<HiddenScanRow>

    @Query("UPDATE channel_scan SET hidden = 0 WHERE itemId IN (:ids)")
    suspend fun unhide(ids: List<Long>)

    /** Channels that failed in at least one scan and still exist, hidden ones first, then the most recent. */
    @Query(
        """
        SELECT s.itemId, i.name, i.logo, i.groupName, s.failures, s.lastFailAt, s.reason, s.hidden
        FROM channel_scan s JOIN items i ON i.id = s.itemId
        WHERE s.failures > 0
        ORDER BY s.hidden DESC, s.lastFailAt DESC
        """,
    )
    fun report(): Flow<List<ScanReportRow>>
}

/** The Shows tab's per-show table ([ShowGroupEntity]). */
@Dao
interface ShowDao {
    /**
     * The Shows tab when nothing narrows it: grouped from [ShowGroupEntity], a few rows per show, instead of from
     * every episode. New episodes are counted only for the shows on screen.
     */
    @Query(
        """
        SELECT s.showKey, MIN(s.showName) AS showName, MAX(s.logo) AS logo, MIN(s.groupName) AS groupName,
            SUM(s.episodes) AS episodes,
            CASE WHEN s.showKey IN (SELECT showKey FROM seen_episodes) THEN (
                SELECT COUNT(*) FROM items e
                WHERE e.kind = 2 AND e.showKey = s.showKey AND e.id NOT IN (SELECT itemId FROM seen_episodes)
            ) ELSE 0 END AS newEpisodes
        FROM show_groups s
        WHERE (s.region IS NULL OR s.region NOT IN (:hidden)) AND ('g:2:' || s.groupName) NOT IN (:hidden)
            AND (:adult OR s.adult = 0)
            AND (:group IS NULL OR s.groupName = :group)
        GROUP BY s.showKey
        ORDER BY MIN(s.sortOrder)
        """,
    )
    fun showsTab(
        hidden: List<String>,
        adult: Boolean,
        group: String?,
    ): PagingSource<Int, ShowSummary>

    @Query("DELETE FROM show_groups")
    suspend fun deleteShowGroups()

    /** [ItemDao.groups] for Shows, from the per-show table: the same counts without reading every episode. */
    @Query(
        """
        SELECT groupName, SUM(episodes) AS count FROM show_groups
        WHERE (region IS NULL OR region NOT IN (:hidden)) AND ('g:2:' || groupName) NOT IN (:hidden) AND (:adult OR adult = 0)
        GROUP BY groupName ORDER BY MIN(sortOrder)
        """,
    )
    fun showGroupChips(
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<GroupCount>>

    /**
     * Episodes already fetched for shows read from the Xtream API (`core/XtreamApi`): every episode with a real link.
     * A refresh keeps them in place of the show's stand-in row, so an opened show doesn't fold back to one row.
     */
    @Query("SELECT * FROM items WHERE kind = 2 AND url NOT LIKE 'xtream-series:%'")
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

/** What a Live TV row shows: the channel and what's on now. */
private const val CHANNEL_COLUMNS =
    "i.id, i.name, i.logo, i.groupName, i.tvgId, p.title AS nowTitle, p.start AS nowStart, p.stop AS nowStop"

/**
 * What's on now: the latest programme started on the channel, found through the (channelId, start) index. One row per
 * channel by construction, so the lists need no GROUP BY, which made SQLite build and sort the whole list per page.
 */
private const val NOW_JOIN =
    "LEFT JOIN programmes p ON p.rowId = (SELECT rowId FROM programmes WHERE channelId = i.tvgId AND start <= :now " +
        "AND stop > :now ORDER BY start DESC LIMIT 1)"

/** Hidden countries, groups, dead channels and empty event slots stay out; the group chip and the search narrow. */
private const val CHANNEL_FILTER =
    "i.kind = 0 AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:0:' || i.groupName) NOT IN (:hidden) " +
        "AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1)) " +
        "AND NOT ('placeholder' IN (:hidden) AND ((' ' || i.searchName) GLOB '* no event*' " +
        "OR (' ' || i.searchName) GLOB '* no live event*')) " +
        "AND NOT ('247' IN (:hidden) AND ((' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*' " +
        "OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]247[^0-9]*' " +
        "OR (' ' || lower(i.groupName || ' ' || i.name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*')) " +
        "AND (:adult OR i.adult = 0) AND (:group IS NULL OR i.groupName = :group) " +
        "AND (:query = '' OR i.id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match) OR i.tvgId IN (" +
        // An event search ("UFC") also finds the channels showing it now or within the hour.
        "SELECT channelId FROM programmes WHERE start > :now - 86400000 AND start < :now + 3600000 AND stop > :now " +
        "AND title LIKE '%' || :query || '%'))"
