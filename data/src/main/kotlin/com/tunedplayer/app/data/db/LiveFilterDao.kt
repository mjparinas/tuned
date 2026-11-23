package com.tunedplayer.app.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What Live TV's quick filters ([com.tunedplayer.app.ui.browse.LiveQuick]) can offer; the narrowing itself is [LIVE_QUICK]. */
@Dao
interface LiveFilterDao {
    /**
     * The countries among the live channels on show, with how many each has. [visible] is the countries still on in
     * Settings (often only a few, like US and Canada), so SQLite seeks the region index instead of scanning every live
     * channel and testing a long NOT IN of every hidden tag. Hidden groups and a kids profile still leave those out.
     */
    @Query(
        "SELECT i.region, COUNT(*) AS count FROM items i WHERE i.kind = 0 AND i.region IN (:visible) " +
            "AND ('g:0:' || i.groupName) NOT IN (:hiddenGroups) AND (:adult OR i.adult = 0) $KIDS_ONLY_I " +
            "GROUP BY i.region",
    )
    fun liveRegions(
        visible: List<String>,
        hiddenGroups: List<String>,
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<RegionCount>>

    /**
     * Same as [liveRegions] when Settings has not yet remembered which countries exist ([AppSettings.seenRegions] empty):
     * walk the live channels and drop the hidden tags. Slower on a big playlist; only used until the first settle.
     */
    @Query(
        "SELECT i.region, COUNT(*) AS count FROM items i WHERE i.kind = 0 AND i.region IS NOT NULL " +
            "AND i.region NOT IN (:hidden) AND ('g:0:' || i.groupName) NOT IN (:hidden) AND (:adult OR i.adult = 0) $KIDS_ONLY_I " +
            "GROUP BY i.region",
    )
    fun liveRegionsFallback(
        hidden: List<String>,
        adult: Boolean,
    ): Flow<List<RegionCount>>

    /** Whether the playlist has any live channel marked adult, so the Adult filter is offered only where it finds some. */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind = 0 AND adult = 1)")
    fun hasAdultChannels(): Flow<Boolean>

    /** Whether any live channel's name has a quality's words ([com.tunedplayer.app.core.ChannelQuality.fullText]). */
    @Query("SELECT EXISTS(SELECT 1 FROM items WHERE kind = 0 AND id IN (SELECT docid FROM items_fts WHERE items_fts MATCH :match))")
    suspend fun hasNamed(match: String): Boolean

    /** Whether the guide has anything from now on, so Has guide is offered only with a guide. */
    @Query("SELECT EXISTS(SELECT 1 FROM programmes WHERE stop > :now)")
    suspend fun hasGuide(now: Long): Boolean

    /** Whether any channel keeps recordings, so Catch-up is offered only where it finds some. */
    @Query("SELECT EXISTS(SELECT 1 FROM catchup_items)")
    suspend fun hasCatchUp(): Boolean

    /** Whether this profile has watched a live channel, so Recently watched is offered only then. */
    @Query("SELECT EXISTS(SELECT 1 FROM history h JOIN items i ON i.id = h.itemId WHERE h.profileId = $ME AND i.kind = 0)")
    suspend fun hasWatchedChannels(): Boolean

    /** The kinds of programme on now ([com.tunedplayer.app.core.ProgrammeGenre.code]), for On now's chips. */
    @Query("SELECT DISTINCT genre FROM programmes WHERE stop > :now AND start <= :now AND genre > 0")
    suspend fun genresOnNow(now: Long): List<Int>
}
