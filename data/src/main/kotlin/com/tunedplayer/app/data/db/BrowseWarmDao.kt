package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * First-page Room reads for speculative warm ([com.tunedplayer.app.work.BrowseWarm]): logos and chips without the full
 * filter SQL, so the next tab's page and Coil enqueue are ready without opening a stream.
 */
@Dao
interface BrowseWarmDao {
    @Query("SELECT id, name, logo FROM items WHERE kind = :kind ORDER BY sortOrder LIMIT :limit")
    suspend fun warmArt(
        kind: Int,
        limit: Int,
    ): List<WarmArtRow>

    @Query(
        """
        SELECT groupName, COUNT(*) AS count FROM items
        WHERE kind = :kind
        GROUP BY groupName ORDER BY MIN(sortOrder)
        LIMIT :limit
        """,
    )
    suspend fun warmGroups(
        kind: Int,
        limit: Int,
    ): List<GroupCount>

    @Query(
        """
        SELECT showKey, showName, logo FROM show_groups
        GROUP BY showKey
        ORDER BY MIN(sortOrder)
        LIMIT :limit
        """,
    )
    suspend fun warmShowArt(limit: Int): List<WarmShowArtRow>

    @Query(
        """
        SELECT groupName, SUM(episodes) AS count FROM show_groups
        GROUP BY groupName ORDER BY MIN(sortOrder)
        LIMIT :limit
        """,
    )
    suspend fun warmShowGroups(limit: Int): List<GroupCount>
}
