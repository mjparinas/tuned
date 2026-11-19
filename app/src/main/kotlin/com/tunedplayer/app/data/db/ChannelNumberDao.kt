package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Query

/** Live channels' numbers ([ChannelNumbers]), for typing one on the remote. */
@Dao
interface ChannelNumberDao {
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind = 0 AND number = 0)")
    suspend fun hasUnnumbered(): Boolean

    @Query("SELECT id FROM items WHERE kind = 0 ORDER BY sortOrder")
    suspend fun liveIdsInOrder(): List<Long>

    /** The live channel with number [number] (typed on the remote), through the (kind, number) index. */
    @Query("SELECT * FROM items WHERE kind = 0 AND number = :number LIMIT 1")
    suspend fun liveByNumber(number: Int): ItemEntity?
}
