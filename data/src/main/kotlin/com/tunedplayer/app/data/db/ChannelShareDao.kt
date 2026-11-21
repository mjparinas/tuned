package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Query

/** The reads behind the copy of the channel list and guide the website's Channels and guide page uses (`data/SharedChannels`). */
@Dao
interface ChannelShareDao {
    /** Every live channel in playlist order. */
    @Query("SELECT name, groupName, logo, tvgId, number, url, region, adult FROM items WHERE kind = 0 ORDER BY sortOrder")
    suspend fun liveChannels(): List<SharedChannelRow>

    /** [channelIds]' programmes on at any point from [from] to [to]. */
    @Query("SELECT channelId, start, stop, title, genre FROM programmes WHERE channelId IN (:channelIds) AND stop > :from AND start < :to")
    suspend fun guideWindow(
        channelIds: List<String>,
        from: Long,
        to: Long,
    ): List<ProgrammeSlot>
}
