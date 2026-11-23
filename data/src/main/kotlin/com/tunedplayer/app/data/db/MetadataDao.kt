package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MetadataDao {
    @Upsert
    suspend fun upsert(row: MetadataEntity)

    @Query("SELECT * FROM metadata WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): MetadataEntity?

    /** Poster URLs already on the device for a batch of cache keys (browse warm Coil enqueue). */
    @Query("SELECT poster FROM metadata WHERE cacheKey IN (:keys) AND found = 1 AND poster IS NOT NULL AND poster != ''")
    suspend fun posters(keys: List<String>): List<String>

    @Upsert
    suspend fun upsertSeason(row: TmdbSeasonEntity)

    @Query("SELECT * FROM tmdb_seasons WHERE cacheKey = :cacheKey")
    suspend fun season(cacheKey: String): TmdbSeasonEntity?
}
