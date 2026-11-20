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

    @Upsert
    suspend fun upsertSeason(row: TmdbSeasonEntity)

    @Query("SELECT * FROM tmdb_seasons WHERE cacheKey = :cacheKey")
    suspend fun season(cacheKey: String): TmdbSeasonEntity?
}
