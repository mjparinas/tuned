package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * The browse lists a page at a time, for [QuickPagingSource]: the SQL is the DAOs' own ([QuickLists]), read with a
 * LIMIT and OFFSET and counted apart, so the first rows never wait on the count.
 */
@Dao
interface QuickDao {
    @RawQuery
    suspend fun channels(query: SupportSQLiteQuery): List<ChannelWithNow>

    @RawQuery
    suspend fun items(query: SupportSQLiteQuery): List<ItemEntity>

    @RawQuery
    suspend fun shows(query: SupportSQLiteQuery): List<ShowSummary>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int
}
