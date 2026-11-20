package com.tunedplayer.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "programmes", indices = [Index(value = ["channelId", "start"]), Index(value = ["stop"]), Index(value = ["start"])])
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    /** What kind of programme it is ([com.tunedplayer.app.core.ProgrammeGenre.code]), 0 when the guide doesn't say. */
    @ColumnInfo(defaultValue = "0") val genre: Int = 0,
)

/** A programme's channel, times and title, for the copy the website's Channels and guide page reads. */
data class ProgrammeSlot(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
)

/**
 * The live channels that keep recordings, by item id, so Live TV's Catch-up filter reads them with one join. Rebuilt
 * with [CatchUpEntity], which is keyed by stream id or link rather than item.
 */
@Entity(tableName = "catchup_items")
data class CatchUpItemEntity(
    @PrimaryKey val itemId: Long,
)

/**
 * A live channel that keeps recordings, replaced on every playlist refresh. Xtream channels (`tv_archive`) are keyed
 * by stream id; plain M3U channels by [com.tunedplayer.app.core.CatchUp.key] with their link template in [source]
 * ([com.tunedplayer.app.core.M3uCatchUp.encode]).
 */
@Entity(tableName = "catchup")
data class CatchUpEntity(
    @PrimaryKey val streamId: String,
    val days: Int,
    val source: String? = null,
)

/** A programme on a sports or pay-per-view channel, for Live Events ([ItemDao.eventProgrammes]). */
data class EventRow(
    val itemId: Long,
    val channelName: String,
    val group: String,
    val title: String,
    val start: Long,
    val stop: Long,
)
