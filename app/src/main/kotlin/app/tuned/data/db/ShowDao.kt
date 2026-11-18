package app.tuned.data.db

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
    @Query(
        """
        SELECT s.showKey, MIN(s.showName) AS showName, MAX(s.logo) AS logo, MIN(s.groupName) AS groupName,
            SUM(s.episodes) AS episodes,
            CASE WHEN s.showKey IN (SELECT showKey FROM seen_episodes WHERE profileId = $ME) THEN (
                SELECT COUNT(*) FROM items e
                WHERE e.kind = 2 AND e.showKey = s.showKey AND e.id NOT IN (SELECT itemId FROM seen_episodes WHERE profileId = $ME)
            ) ELSE 0 END AS newEpisodes
        FROM show_groups s
        WHERE (s.region IS NULL OR s.region NOT IN (:hidden)) AND ('g:2:' || s.groupName) NOT IN (:hidden)
            AND (:adult OR s.adult = 0)
            $KIDS_ONLY_S
            $ROUND_THE_CLOCK_S
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
