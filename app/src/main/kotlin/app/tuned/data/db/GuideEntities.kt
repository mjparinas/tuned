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

/** A live channel that keeps recordings (Xtream `tv_archive`), by its stream id; replaced on every playlist refresh. */
@Entity(tableName = "catchup")
data class CatchUpEntity(
    @PrimaryKey val streamId: String,
    val days: Int,
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
