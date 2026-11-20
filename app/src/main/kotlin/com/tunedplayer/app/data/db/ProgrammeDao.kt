package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

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

    /** A channel's programmes on at any point from [from] to [to], for one block of the grid guide. */
    @Query("SELECT * FROM programmes WHERE channelId = :channelId AND start < :to AND stop > :from ORDER BY start")
    suspend fun between(
        channelId: String,
        from: Long,
        to: Long,
    ): List<ProgrammeEntity>

    /** What's on now on each of [channelIds], for the player's mini guide. */
    @Query("SELECT * FROM programmes WHERE channelId IN (:channelIds) AND start <= :now AND stop > :now")
    suspend fun onNow(
        channelIds: List<String>,
        now: Long,
    ): List<ProgrammeEntity>

    /** [channelIds]' programmes on at any point from [from] to [to], for the website's copy of the guide. */
    @Query("SELECT channelId, start, stop, title FROM programmes WHERE channelId IN (:channelIds) AND stop > :from AND start < :to")
    suspend fun window(
        channelIds: List<String>,
        from: Long,
        to: Long,
    ): List<ProgrammeSlot>

    @Query("SELECT COUNT(*) FROM programmes")
    suspend fun count(): Int

    /** Whole-guide search; the query is built by [GuideSearchIndex.search], since Room doesn't know its index. */
    @RawQuery
    suspend fun searchGuide(query: SupportSQLiteQuery): List<GuideHit>
}
