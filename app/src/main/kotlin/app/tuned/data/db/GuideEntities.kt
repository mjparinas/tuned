package app.tuned.data.db

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
)

/**
 * A live channel that keeps recordings, replaced on every playlist refresh. Xtream channels (`tv_archive`) are keyed
 * by stream id; plain M3U channels by [app.tuned.core.CatchUp.key] with their link template in [source]
 * ([app.tuned.core.M3uCatchUp.encode]).
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
