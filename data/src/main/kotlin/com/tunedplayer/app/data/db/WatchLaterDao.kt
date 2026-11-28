package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    /** Favourites that aren't already there, leaving one that is as it was ([com.tunedplayer.app.data.WatchLaterFold]). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavouritesIfMissing(rows: List<FavouriteEntity>)
}
