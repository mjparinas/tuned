package app.tuned.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stream from the playlist. Rows are replaced as a whole on every refresh, inside one transaction. */
@Entity(
    tableName = "items",
    indices = [
        Index(value = ["kind", "sortOrder"]),
        Index(value = ["kind", "groupName"]),
        Index(value = ["kind", "showKey", "season", "episode"]),
        Index(value = ["tvgId"]),
        Index(value = ["region"]),
    ],
)
data class ItemEntity(
    @PrimaryKey val id: Long,
    val kind: Int,
    val name: String,
    val searchName: String,
    val groupName: String,
    val region: String?,
    val adult: Boolean,
    val logo: String?,
    val url: String,
    val tvgId: String?,
    val showKey: String?,
    val showName: String?,
    val season: Int?,
    val episode: Int?,
    val sortOrder: Int,
)

@Entity(tableName = "programmes", indices = [Index(value = ["channelId", "start"]), Index(value = ["stop"])])
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/** Favourites and watch history survive refreshes because they hold the stable id, not a row. */
@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val itemId: Long,
    val addedAt: Long,
)

@Entity(tableName = "history", indices = [Index(value = ["watchedAt"])])
data class HistoryEntity(
    @PrimaryKey val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
)

/** A live channel with what's on now (from the guide), for the Live TV list. */
data class ChannelWithNow(
    val id: Long,
    val name: String,
    val logo: String?,
    val groupName: String,
    val tvgId: String?,
    @ColumnInfo(name = "nowTitle") val nowTitle: String?,
    @ColumnInfo(name = "nowStart") val nowStart: Long?,
    @ColumnInfo(name = "nowStop") val nowStop: Long?,
    @ColumnInfo(name = "favourite") val favourite: Boolean,
)

data class ShowSummary(
    val showKey: String,
    val showName: String,
    val logo: String?,
    val groupName: String,
    val episodes: Int,
)

data class GroupCount(
    val groupName: String,
    val count: Int,
)

data class RegionCount(
    val region: String,
    val count: Int,
)
