package app.tuned.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT COUNT(*) FROM items")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM items")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): ItemEntity?

    @Query("SELECT DISTINCT tvgId FROM items WHERE kind = 0 AND tvgId IS NOT NULL")
    suspend fun liveTvgIds(): List<String>

    @Query(
        """
        SELECT i.id, i.name, i.logo, i.groupName, i.tvgId,
            p.title AS nowTitle, p.start AS nowStart, p.stop AS nowStop,
            (f.itemId IS NOT NULL) AS favourite
        FROM items i
        LEFT JOIN programmes p ON p.channelId = i.tvgId AND p.start <= :now AND p.stop > :now
        LEFT JOIN favourites f ON f.itemId = i.id
        WHERE i.kind = 0
            AND (i.region IS NULL OR i.region NOT IN (:hidden))
            AND (:adult OR i.adult = 0)
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR f.itemId IS NOT NULL)
            AND (:query = '' OR i.searchName LIKE '%' || :query || '%')
        GROUP BY i.id
        ORDER BY i.sortOrder
        """,
    )
    fun channels(
        now: Long,
        hidden: List<String>,
        adult: Boolean,
        group: String?,
        favouritesOnly: Boolean,
        query: String,
    ): PagingSource<Int, ChannelWithNow>

    @Query(
        """
        SELECT * FROM items i
        WHERE i.kind = :kind
            AND (i.region IS NULL OR i.region NOT IN (:hidden))
            AND (:adult OR i.adult = 0)
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites))
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
        SELECT showKey, MIN(showName) AS showName, MAX(logo) AS logo, MIN(groupName) AS groupName, COUNT(*) AS episodes
        FROM items i
        WHERE i.kind = 2 AND i.showKey IS NOT NULL
            AND (i.region IS NULL OR i.region NOT IN (:hidden))
            AND (:adult OR i.adult = 0)
            AND (:group IS NULL OR i.groupName = :group)
            AND (:favouritesOnly = 0 OR i.id IN (SELECT itemId FROM favourites))
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

    @Query("SELECT * FROM items WHERE kind = 2 AND showKey = :showKey ORDER BY season, episode, sortOrder")
    fun episodes(showKey: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE kind = 2 AND showKey = :showKey ORDER BY season, episode, sortOrder")
    suspend fun episodesNow(showKey: String): List<ItemEntity>

    @Query(
        """
        SELECT * FROM items
        WHERE kind = 0 AND groupName = :group AND (region IS NULL OR region NOT IN (:hidden)) AND (:adult OR adult = 0)
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
        WHERE kind = :kind AND (region IS NULL OR region NOT IN (:hidden)) AND (:adult OR adult = 0)
        GROUP BY groupName ORDER BY MIN(sortOrder)
        """,
    )
    fun groups(
        kind: Int,
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<GroupCount>>

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

    @Query("SELECT COUNT(*) FROM programmes")
    suspend fun count(): Int
}

@Dao
interface LibraryDao {
    @Upsert
    suspend fun addFavourite(favourite: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE itemId = :itemId")
    suspend fun removeFavourite(itemId: Long)

    @Query("SELECT itemId FROM favourites")
    fun favouriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE itemId = :itemId)")
    fun isFavourite(itemId: Long): Flow<Boolean>

    @Upsert
    suspend fun saveProgress(history: HistoryEntity)

    @Query("SELECT * FROM history WHERE itemId = :itemId")
    suspend fun progress(itemId: Long): HistoryEntity?

    @Query("SELECT * FROM history WHERE itemId IN (:ids)")
    fun progressFor(ids: List<Long>): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM favourites")
    suspend fun clearFavourites()
}
