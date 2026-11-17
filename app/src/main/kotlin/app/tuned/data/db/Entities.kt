package app.tuned.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
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

/** A download with the artwork of the film or episode it saves, for the Downloads page. */
data class DownloadRow(
    @Embedded val download: DownloadEntity,
    val logo: String?,
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
    /** One person per line: name, character and photo link, tab-separated. */
    val cast: String,
    val trailer: String?,
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

@Entity(tableName = "programmes", indices = [Index(value = ["channelId", "start"]), Index(value = ["stop"])])
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Favourites and watch history survive refreshes because they hold the stable id, not a row. Each profile keeps its
 * own ([profileId]); the DAOs fill it in with the profile in use, so callers leave it out.
 */
@Entity(tableName = "favourites", primaryKeys = ["profileId", "itemId"])
data class FavouriteEntity(
    val itemId: Long,
    val addedAt: Long,
    /** Where it sits in the Favourites list (Settings › Order favourites); new ones go to the end. */
    @ColumnInfo(defaultValue = "0") val position: Long = addedAt,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
)

@Entity(tableName = "history", primaryKeys = ["profileId", "itemId"], indices = [Index(value = ["watchedAt"])])
data class HistoryEntity(
    val itemId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val watchedAt: Long,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
)

/** Who's watching (docs/decisions/profiles.md). A kids profile sees kids and family categories only. */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Blank for the first profile until it's named; shown as "Me". */
    val name: String,
    /** An index into the accent colours, for the round badge. */
    val colour: Int,
    val kids: Boolean,
    /** A [app.tuned.core.PinHash] when the profile is locked, else null. */
    val pinHash: String?,
    val createdAt: Long,
)

/** The one row saying which profile is in use. Queries read it in SQL, so a switch refreshes every list at once. */
@Entity(tableName = "current_profile")
data class CurrentProfileEntity(
    @PrimaryKey val slot: Int = 0,
    val profileId: Long,
)

object Profiles {
    /** The profile every install starts with, and the one older favourites and history belong to. */
    const val FIRST = 1L
}

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
@Entity(tableName = "watch_later", primaryKeys = ["profileId", "key"])
data class WatchLaterEntity(
    val key: String,
    val itemId: Long?,
    val showKey: String?,
    val title: String,
    val image: String?,
    val addedAt: Long,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
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
@Entity(tableName = "seen_episodes", primaryKeys = ["profileId", "itemId"], indices = [Index("showKey")])
data class SeenEpisodeEntity(
    val itemId: Long,
    val showKey: String,
    @ColumnInfo(defaultValue = "1") val profileId: Long = Profiles.FIRST,
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
