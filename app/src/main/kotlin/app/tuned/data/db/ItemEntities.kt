package app.tuned.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** One stream from the playlist. Each refresh updates rows in place by their stable id ([PlaylistWriter]). */
@Entity(
    tableName = "items",
    indices = [
        Index(value = ["kind", "sortOrder"]),
        Index(value = ["kind", "groupName"]),
        Index(value = ["kind", "showKey", "season", "episode"]),
        Index(value = ["tvgId"]),
        Index(value = ["region"]),
        Index(value = ["kind", "number"]),
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
    /** Live channels' numbers, 1 up in playlist order, for typing a number on the remote (0 for films and episodes). */
    @ColumnInfo(defaultValue = "0") val number: Int = 0,
)

/**
 * The full-text index over stream names that search runs on: whole words and the start of words ("top gu" finds
 * "Top Gun"), straight from an index instead of reading every name. Room keeps it in step with `items` by triggers.
 */
@Fts4(contentEntity = ItemEntity::class)
@Entity(tableName = "items_fts")
data class ItemFts(
    val searchName: String,
)

/** A stream's id and link, to match it with what the Xtream API says about its stream id. */
data class ItemUrl(
    val id: Long,
    val url: String,
)

/** A live channel's guide id and link, to match it with its catch-up days. */
data class LiveLink(
    val tvgId: String,
    val url: String,
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
    /** The channel's number ([ItemEntity.number]); 0 until numbered. */
    val number: Int = 0,
)

data class GroupCount(
    val groupName: String,
    val count: Int,
)

data class RegionCount(
    val region: String,
    val count: Int,
)

/**
 * One row per show and the group, country and adult flag its episodes are listed under, rebuilt from `items` after every
 * playlist refresh ([ShowGroups]). The Shows tab groups these few thousand rows instead of every episode, which on a
 * big playlist took a phone seconds per page.
 */
@Entity(tableName = "show_groups", indices = [Index("showKey"), Index("sortOrder")])
data class ShowGroupEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val showKey: String,
    val groupName: String,
    val region: String?,
    val adult: Boolean,
    val showName: String,
    val logo: String?,
    val episodes: Int,
    val sortOrder: Int,
)

data class ShowSummary(
    val showKey: String,
    val showName: String,
    val logo: String?,
    val groupName: String,
    val episodes: Int,
    /** Episodes added since the show was last opened; 0 for a show never opened (see [SeenEpisodeEntity]). */
    val newEpisodes: Int,
)
