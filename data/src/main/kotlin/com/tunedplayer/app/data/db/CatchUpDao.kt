package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatchUpDao {
    @Query("DELETE FROM catchup")
    suspend fun deleteRows()

    @Query("DELETE FROM catchup_items")
    suspend fun deleteItems()

    /** Forgets every channel's catch-up, and which items have it. */
    @Transaction
    suspend fun deleteAll() {
        deleteRows()
        deleteItems()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(rows: List<CatchUpItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CatchUpEntity>)

    @Query("SELECT * FROM catchup")
    suspend fun all(): List<CatchUpEntity>

    /** The longest catch-up any channel offers, so the grid guide scrolls back that far. */
    @Query("SELECT MAX(days) FROM catchup")
    suspend fun maxDays(): Int?

    @Query("SELECT * FROM catchup WHERE streamId = :key")
    suspend fun get(key: String): CatchUpEntity?

    @Query("SELECT days FROM catchup WHERE streamId = :streamId")
    fun days(streamId: String): Flow<Int?>

    // What the Xtream API says about live channels, beside their catch-up days: both come from get_live_streams.
    @Query("SELECT id, url FROM items WHERE kind = 0")
    suspend fun liveUrls(): List<ItemUrl>

    /** Marks live channels the server itself calls adult (Xtream `is_adult`), whatever their category says. */
    @Query("UPDATE items SET adult = 1 WHERE id IN (:ids)")
    suspend fun markAdult(ids: List<Long>)
}
