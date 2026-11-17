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

/** A live channel that keeps recordings (Xtream `tv_archive`), by its stream id; replaced on every playlist refresh. */
@Entity(tableName = "catchup")
data class CatchUpEntity(
    @PrimaryKey val streamId: String,
    val days: Int,
)

/** A live channel's guide id and link, to match it with its catch-up days. */
data class LiveLink(
    val tvgId: String,
    val url: String,
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
    /** Where it sits in the Favourites list (Settings › Order favourites); new ones go to the end. */
    @ColumnInfo(defaultValue = "0") val position: Long = addedAt,
)

@Entity(tableName = "history", indices = [Index(value = ["watchedAt"])])
data class HistoryEntity(
    @PrimaryKey val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
)

/** What auto-tune learned about a live channel: when it last played or failed, and how sharp it was. */
@Entity(tableName = "channel_health")
data class ChannelHealthEntity(
    @PrimaryKey val itemId: Long,
    val okAt: Long,
    val deadAt: Long,
    val height: Int,
)

/** A live channel an event search found, with its health, for auto-tune to rank. */
data class TuneRow(
    val id: Long,
    val name: String,
    val groupName: String,
    val region: String?,
    val nameMatch: Boolean,
    val guideMatch: Boolean,
    val okAt: Long?,
    val deadAt: Long?,
    val height: Int?,
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

/** A movie or episode to pick up where it was left. */
data class ContinueRow(
    val id: Long,
    val name: String,
    val logo: String?,
    val showName: String?,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

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

/** A favourite channel in Settings › Order favourites. */
data class FavouriteRow(
    val id: Long,
    val name: String,
    val logo: String?,
    val tvgId: String?,
)
