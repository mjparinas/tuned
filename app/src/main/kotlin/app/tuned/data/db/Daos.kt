package app.tuned.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The profile in use; 1 (the first profile) when none is set, as in a fresh test database. */
private const val ME = "COALESCE((SELECT profileId FROM current_profile), 1)"

/** Kids profiles: only kids and family categories ([app.tuned.core.KidsContent], keep the two in step). */
private const val KIDS_ONLY_I =
    "AND NOT ('kids' IN (:hidden) AND NOT (" +
        "lower(i.groupName) GLOB '*kid*' OR " +
        "lower(i.groupName) GLOB '*child*' OR " +
        "lower(i.groupName) GLOB '*cartoon*' OR " +
        "lower(i.groupName) GLOB '*family*' OR " +
        "lower(i.groupName) GLOB '*junior*' OR " +
        "lower(i.groupName) GLOB '*disney*' OR " +
        "lower(i.groupName) GLOB '*nick*' OR " +
        "lower(i.groupName) GLOB '*baby*' OR " +
        "lower(i.groupName) GLOB '*animation*' OR " +
        "lower(i.groupName) GLOB '*boomerang*' OR " +
        "(i.kind = 0 AND (" +
        "i.searchName GLOB '*kid*' OR " +
        "i.searchName GLOB '*child*' OR " +
        "i.searchName GLOB '*cartoon*' OR " +
        "i.searchName GLOB '*junior*' OR " +
        "i.searchName GLOB '*disney*' OR " +
        "i.searchName GLOB '*nick*' OR " +
        "i.searchName GLOB '*baby*' OR " +
        "i.searchName GLOB '*boomerang*'" +
        "))))"

/** [KIDS_ONLY_I] for queries that don't name the table. */
private const val KIDS_ONLY =
    "AND NOT ('kids' IN (:hidden) AND NOT (" +
        "lower(groupName) GLOB '*kid*' OR " +
        "lower(groupName) GLOB '*child*' OR " +
        "lower(groupName) GLOB '*cartoon*' OR " +
        "lower(groupName) GLOB '*family*' OR " +
        "lower(groupName) GLOB '*junior*' OR " +
        "lower(groupName) GLOB '*disney*' OR " +
        "lower(groupName) GLOB '*nick*' OR " +
        "lower(groupName) GLOB '*baby*' OR " +
        "lower(groupName) GLOB '*animation*' OR " +
        "lower(groupName) GLOB '*boomerang*' OR " +
        "(kind = 0 AND (" +
        "searchName GLOB '*kid*' OR " +
        "searchName GLOB '*child*' OR " +
        "searchName GLOB '*cartoon*' OR " +
        "searchName GLOB '*junior*' OR " +
        "searchName GLOB '*disney*' OR " +
        "searchName GLOB '*nick*' OR " +
        "searchName GLOB '*baby*' OR " +
        "searchName GLOB '*boomerang*'" +
        "))))"

/**
 * Every list query takes the same filters: hidden regions, whether adult categories show, an optional category and
 * an optional search. Region NULL (no country found) always shows.
 */
@Dao
interface ItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ItemEntity>)

    @Query("DELETE FROM items")
    suspend fun deleteAll()

    @Query("SELECT id FROM items")
    suspend fun allIds(): List<Long>

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

    @Query(
        """
        SELECT i.id, i.name, i.logo, i.groupName, i.tvgId,
            p.title AS nowTitle, p.start AS nowStart, p.stop AS nowStop,
            (f.itemId IS NOT NULL) AS favourite
        FROM items i
        LEFT JOIN programmes p ON p.channelId = i.tvgId AND p.start <= :now AND p.stop > :now
        LEFT JOIN favourites f ON f.itemId = i.id AND f.profileId = $ME
        LEFT JOIN history h ON h.itemId = i.id AND h.profileId = $ME
        WHERE i.kind = 0
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR f.itemId IS NOT NULL)
            AND (:recentOnly = 0 OR h.itemId IS NOT NULL)
            AND (:query = '' OR i.searchName LIKE '%' || :query || '%' OR i.tvgId IN (
                -- An event search ("UFC") also finds the channels showing it now or within the hour.
                SELECT channelId FROM programmes WHERE title LIKE '%' || :query || '%' AND stop > :now AND start < :now + 3600000
            ))
        GROUP BY i.id
        ORDER BY CASE WHEN :recentOnly = 1 THEN h.watchedAt END DESC, CASE WHEN :favouritesOnly = 1 THEN f.position END, i.sortOrder
        """,
    )
    fun channels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        recentOnly: Boolean,
        query: String,
    ): PagingSource<Int, ChannelWithNow>

    /** Movies or episodes started and not finished, last watched first, for the Continue Watching row. */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.showName, i.season, i.episode, h.positionMs, h.durationMs
        FROM history h
        JOIN items i ON i.id = h.itemId
        WHERE h.profileId = $ME AND i.kind = :kind
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
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
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites WHERE profileId = $ME))
            AND (:query = '' OR i.searchName LIKE '%' || :query || '%')
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
    ): PagingSource<Int, ItemEntity>

    @Query(
        """
        SELECT showKey, MIN(showName) AS showName, MAX(logo) AS logo, MIN(groupName) AS groupName, COUNT(*) AS episodes,
            SUM(CASE WHEN i.showKey IN (SELECT showKey FROM seen_episodes WHERE profileId = $ME)
                AND i.id NOT IN (SELECT itemId FROM seen_episodes WHERE profileId = $ME)
                THEN 1 ELSE 0 END) AS newEpisodes
        FROM items i
        WHERE i.kind = 2 AND i.showKey IS NOT NULL
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites WHERE profileId = $ME))
            AND (:query = '' OR i.searchName LIKE '%' || :query || '%')
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
    ): PagingSource<Int, ShowSummary>

    /** Search everywhere: the first few live channels for a search (names, or what's on now and within the hour). */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.groupName, i.tvgId,
            p.title AS nowTitle, p.start AS nowStart, p.stop AS nowStop,
            (f.itemId IS NOT NULL) AS favourite
        FROM items i
        LEFT JOIN programmes p ON p.channelId = i.tvgId AND p.start <= :now AND p.stop > :now
        LEFT JOIN favourites f ON f.itemId = i.id AND f.profileId = $ME
        WHERE i.kind = 0
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (i.searchName LIKE '%' || :query || '%' OR i.tvgId IN (
                SELECT channelId FROM programmes WHERE title LIKE '%' || :query || '%' AND stop > :now AND start < :now + 3600000
            ))
        GROUP BY i.id
        ORDER BY (f.itemId IS NULL), i.sortOrder
        LIMIT :limit
        """,
    )
    suspend fun searchChannels(
        query: String,
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        limit: Int,
    ): List<ChannelWithNow>

    @Query(
        """
        SELECT * FROM items i
        WHERE i.kind = 1 AND i.searchName LIKE '%' || :query || '%'
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        ORDER BY i.sortOrder
        LIMIT :limit
        """,
    )
    suspend fun searchMovies(
        query: String,
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
        WHERE i.kind = 2 AND i.showKey IS NOT NULL AND i.searchName LIKE '%' || :query || '%'
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
        GROUP BY showKey
        ORDER BY MIN(sortOrder)
        LIMIT :limit
        """,
    )
    suspend fun searchShows(
        query: String,
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
            $KIDS_ONLY
            AND NOT ('placeholder' IN (:hidden) AND kind = 0
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
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
            $KIDS_ONLY
            AND NOT ('placeholder' IN (:hidden) AND kind = 0
                AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))
        GROUP BY groupName ORDER BY MIN(sortOrder)
        """,
    )
    fun groups(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<GroupCount>>

    /**
     * Auto-tune's candidates: live channels whose name matches the search, or whose guide has a matching programme
     * on now or starting within [to]. `title LIKE` ignores case for English letters, which is what event names use.
     */
    @Query(
        """
        SELECT i.id, i.name, i.groupName, i.region,
            (i.searchName LIKE '%' || :normalized || '%') AS nameMatch,
            (i.tvgId IN (
                SELECT channelId FROM programmes WHERE title LIKE '%' || :query || '%' AND stop > :from AND start < :to
            )) AS guideMatch,
            h.okAt, h.deadAt, h.height
        FROM items i
        LEFT JOIN channel_health h ON h.itemId = i.id
        WHERE i.kind = 0
            AND (i.region IS NULL OR i.region NOT IN (:hidden)) AND ('g:' || i.kind || ':' || i.groupName) NOT IN (:hidden)
            AND NOT ('scan:dead' IN (:hidden) AND i.id IN (SELECT itemId FROM channel_scan WHERE hidden = 1))
            AND NOT ('placeholder' IN (:hidden) AND i.kind = 0
                AND ((' ' || i.searchName) GLOB '* no event*' OR (' ' || i.searchName) GLOB '* no live event*'))
            AND (:adult OR i.adult = 0)
            $KIDS_ONLY_I
            AND (nameMatch OR guideMatch)
        ORDER BY i.sortOrder
        LIMIT 500
        """,
    )
    suspend fun tuneCandidates(
        query: String,
        normalized: String,
        from: Long,
        to: Long,
        hidden: List<String>,
        adult: Boolean,
    ): List<TuneRow>

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

    /** What's on now on each of [channelIds], for the player's mini guide. */
    @Query("SELECT * FROM programmes WHERE channelId IN (:channelIds) AND start <= :now AND stop > :now")
    suspend fun onNow(
        channelIds: List<String>,
        now: Long,
    ): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes")
    suspend fun count(): Int
}

/** The person's own lists, each for the profile in use ([ME]): writes stamp it, reads and deletes keep to it. */
@Dao
interface LibraryDao {
    @Query("SELECT profileId FROM current_profile")
    suspend fun currentProfile(): Long?

    @Upsert
    suspend fun upsertFavourite(favourite: FavouriteEntity)

    @Transaction
    suspend fun addFavourite(favourite: FavouriteEntity) = upsertFavourite(favourite.copy(profileId = currentProfile() ?: Profiles.FIRST))

    @Query("DELETE FROM favourites WHERE itemId = :itemId AND profileId = $ME")
    suspend fun removeFavourite(itemId: Long)

    @Query("SELECT * FROM favourites WHERE profileId = $ME")
    suspend fun allFavourites(): List<FavouriteEntity>

    @Query("SELECT itemId FROM favourites WHERE profileId = $ME")
    fun favouriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE itemId = :itemId AND profileId = $ME)")
    fun isFavourite(itemId: Long): Flow<Boolean>

    /** Favourite channels in the order they're shown, for Settings › Order favourites. */
    @Query(
        """
        SELECT i.id, i.name, i.logo, i.tvgId FROM favourites f JOIN items i ON i.id = f.itemId
        WHERE i.kind = 0 AND f.profileId = $ME
        GROUP BY i.id
        ORDER BY f.position, i.sortOrder
        """,
    )
    fun favouriteChannels(): Flow<List<FavouriteRow>>

    @Query("UPDATE favourites SET position = :position WHERE itemId = :itemId AND profileId = $ME")
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
    suspend fun upsertProgress(history: HistoryEntity)

    @Transaction
    suspend fun saveProgress(history: HistoryEntity) = upsertProgress(history.copy(profileId = currentProfile() ?: Profiles.FIRST))

    @Query("SELECT * FROM history WHERE itemId = :itemId AND profileId = $ME")
    suspend fun progress(itemId: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE itemId IN (:ids) AND profileId = $ME")
    fun progressFor(ids: List<Long>): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history WHERE profileId = $ME")
    suspend fun clearHistory()

    /** Films and episodes counted as watched: played to the end, or marked by hand. */
    @Query("SELECT itemId FROM history WHERE profileId = $ME AND durationMs > 0 AND positionMs >= durationMs * 92 / 100")
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
    @Query("DELETE FROM history WHERE itemId IN (:ids) AND profileId = $ME")
    suspend fun markUnwatched(ids: List<Long>)

    @Query("DELETE FROM favourites WHERE profileId = $ME")
    suspend fun clearFavourites()

    @Query("SELECT * FROM channel_health WHERE itemId = :itemId")
    suspend fun health(itemId: Long): ChannelHealthEntity?

    @Upsert
    suspend fun saveHealth(health: ChannelHealthEntity)
}

/** Who watches: the profiles, and which one is in use (one row in `current_profile`). */
@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun all(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY id")
    suspend fun allNow(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Query("SELECT p.* FROM profiles p JOIN current_profile c ON c.profileId = p.id")
    fun current(): Flow<ProfileEntity?>

    @Query("SELECT p.* FROM profiles p JOIN current_profile c ON c.profileId = p.id")
    suspend fun currentNow(): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Upsert
    suspend fun setCurrent(row: CurrentProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteRow(id: Long)

    @Query("DELETE FROM favourites WHERE profileId = :id")
    suspend fun deleteFavourites(id: Long)

    @Query("DELETE FROM history WHERE profileId = :id")
    suspend fun deleteHistory(id: Long)

    @Query("DELETE FROM watch_later WHERE profileId = :id")
    suspend fun deleteWatchLater(id: Long)

    @Query("DELETE FROM seen_episodes WHERE profileId = :id")
    suspend fun deleteSeen(id: Long)

    /** Removes a profile and everything it kept. The caller has moved off it first. */
    @Transaction
    suspend fun delete(id: Long) {
        deleteFavourites(id)
        deleteHistory(id)
        deleteWatchLater(id)
        deleteSeen(id)
        deleteRow(id)
    }
}

@Dao
interface CatchUpDao {
    @Query("DELETE FROM catchup")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CatchUpEntity>)

    @Query("SELECT * FROM catchup")
    suspend fun all(): List<CatchUpEntity>

    @Query("SELECT days FROM catchup WHERE streamId = :streamId")
    fun days(streamId: String): Flow<Int?>
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

    /** Everything saved or on its way, newest first, with each item's artwork while it's still in the playlist. */
    @Query("SELECT d.*, i.logo AS logo FROM downloads d LEFT JOIN items i ON i.id = d.itemId ORDER BY d.addedAt DESC")
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
}

@Dao
interface WatchLaterDao {
    @Query("SELECT profileId FROM current_profile")
    suspend fun currentProfile(): Long?

    @Upsert
    suspend fun upsert(row: WatchLaterEntity)

    @Transaction
    suspend fun add(row: WatchLaterEntity) = upsert(row.copy(profileId = currentProfile() ?: Profiles.FIRST))

    @Query("DELETE FROM watch_later WHERE `key` = :key AND profileId = $ME")
    suspend fun remove(key: String)

    @Query("SELECT * FROM watch_later WHERE profileId = $ME ORDER BY addedAt DESC")
    fun all(): Flow<List<WatchLaterEntity>>

    @Query("SELECT `key` FROM watch_later WHERE profileId = $ME")
    fun keys(): Flow<List<String>>
}

@Dao
interface SeenEpisodeDao {
    @Query("SELECT profileId FROM current_profile")
    suspend fun currentProfile(): Long?

    @Query("SELECT itemId FROM seen_episodes WHERE showKey = :showKey AND profileId = $ME")
    suspend fun seen(showKey: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<SeenEpisodeEntity>)

    @Transaction
    suspend fun add(rows: List<SeenEpisodeEntity>) {
        val me = currentProfile() ?: Profiles.FIRST
        insert(rows.map { it.copy(profileId = me) })
    }
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
