package app.tuned.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
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

/** A film or episode saved for watching offline. Kept across playlist refreshes, like favourites. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val itemId: Long,
    val name: String,
    val url: String,
    /** Where the finished file is (app storage, removed with the app). */
    val path: String,
    val bytes: Long = 0,
    /** The full size, or -1 while unknown. */
    val total: Long = -1,
    val state: Int = QUEUED,
    val addedAt: Long,
) {
    companion object {
        const val QUEUED = 0
        const val DONE = 1
        const val FAILED = 2

        /** Held back by the person; skipped until they resume it. */
        const val PAUSED = 3
    }
}

/**
 * A download with the artwork of the film or episode it saves, and the show and season it belongs to, for the
 * Downloads page. The extras are null once the item has left the playlist.
 */
data class DownloadRow(
    @Embedded val download: DownloadEntity,
    val logo: String?,
    val showKey: String? = null,
    val showName: String? = null,
    /** The show's poster, as on the Shows tab. */
    val showLogo: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * What TMDB said about a film or show, kept on the device so details and ratings load without the network.
 * [full] is false when only the search answer (rating, summary, artwork) is known, true once the details page has
 * also fetched genres, runtime, cast and trailer. A row with [found] false remembers that nothing matched.
 */
@Entity(tableName = "metadata")
data class MetadataEntity(
    @PrimaryKey val cacheKey: String,
    val found: Boolean,
    val full: Boolean,
    val tmdbId: Long,
    val title: String,
    val year: String?,
    val overview: String?,
    val rating: Double?,
    val poster: String?,
    val backdrop: String?,
    /** Comma-separated. */
    val genres: String,
    val runtime: String?,
    /**
     * One person per line: name, character, photo link and TMDB person id, tab-separated. A person's own row
     * ("person|<id>", see PeopleRepository) keeps their credits here as JSON instead.
     */
    val cast: String,
    val trailer: String?,
    val fetchedAt: Long,
)

/**
 * One season of a show on TMDB, keyed "tmdbId|season": the episodes as JSON (name, summary, runtime and screenshot
 * link), or nothing found. Kept so a season seen once shows its screenshots and names from the device after that.
 */
@Entity(tableName = "tmdb_seasons")
data class TmdbSeasonEntity(
    @PrimaryKey val cacheKey: String,
    val found: Boolean,
    val episodes: String,
    val fetchedAt: Long,
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

@Entity(tableName = "programmes", indices = [Index(value = ["channelId", "start"]), Index(value = ["stop"]), Index(value = ["start"])])
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
    /** Hidden in the lists (dead in a scan, or a "No event" placeholder); auto-tune still tries event channels. */
    val held: Boolean,
)

/** A channel a scan hid, with its name, for [ChannelScanDao.hiddenNames]. */
data class HiddenScanRow(
    val itemId: Long,
    val name: String,
    val groupName: String,
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
    /** Episodes added since the show was last opened; 0 for a show never opened (see [SeenEpisodeEntity]). */
    val newEpisodes: Int,
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

/**
 * A film or a show saved to watch later (Melvin, 2026-11-17). [key] is `m:<item id>` for a film or `s:<show key>` for a
 * show. The title and artwork are kept here, so the list shows without looking anything up.
 */
@Entity(tableName = "watch_later")
data class WatchLaterEntity(
    @PrimaryKey val key: String,
    val itemId: Long?,
    val showKey: String?,
    val title: String,
    val image: String?,
    val addedAt: Long,
) {
    companion object {
        fun movieKey(itemId: Long) = "m:$itemId"

        fun showKey(showKey: String) = "s:$showKey"
    }
}

/**
 * An episode that was in the list the last time its show was opened (Melvin, 2026-11-17: "new episodes"). Episodes of
 * an opened show that aren't here arrived since, and show as new. Ids are stable across refreshes, so this survives them.
 */
@Entity(tableName = "seen_episodes", indices = [Index("showKey")])
data class SeenEpisodeEntity(
    @PrimaryKey val itemId: Long,
    val showKey: String,
)

/**
 * What channel scans found for a live channel ([app.tuned.core.ChannelScan.Record]). [hidden] is stored, not worked
 * out, so the lists can leave hidden channels out in SQL.
 */
@Entity(tableName = "channel_scan")
data class ChannelScanEntity(
    @PrimaryKey val itemId: Long,
    val failures: Int,
    val firstFailAt: Long,
    val lastFailAt: Long,
    val lastOkAt: Long,
    val reason: String?,
    val kept: Boolean,
    val hidden: Boolean,
)

/** A row of the channel health report: the channel and what scans found. */
data class ScanReportRow(
    val itemId: Long,
    val name: String,
    val logo: String?,
    val groupName: String,
    val failures: Int,
    val lastFailAt: Long,
    val reason: String?,
    val hidden: Boolean,
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
