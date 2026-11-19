package app.tuned.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    /** Downloads not finished yet (queued, held or failed), whose partial files a sign-out removes. */
    @Query("SELECT * FROM downloads WHERE state != 1")
    suspend fun unfinished(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE state != 1")
    suspend fun deleteUnfinished()

    /** Forgets every download's stream address (which can hold the login); a finished file plays without it. */
    @Query("UPDATE downloads SET url = ''")
    suspend fun forgetLinks()

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int

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
