package com.tunedplayer.app.data.db

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
        // Movies sorted by newest, year, rating or title read straight down one of these, a page at a time, ties in
        // the provider's order ([com.tunedplayer.app.core.TitleSort]).
        Index(value = ["kind", "added", "sortOrder"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC]),
        Index(value = ["kind", "year", "sortOrder"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC]),
        Index(value = ["kind", "topRating", "sortOrder"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC]),
        Index(value = ["kind", "sortTitle", "sortOrder"]),
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
    /**
     * When the provider added it, in epoch seconds, or when Tuned first saw it when the provider doesn't say; 0 for
     * the very first download of a playlist without times. Newest first sorts on it.
     */
    @ColumnInfo(defaultValue = "0") val added: Long = 0,
    /** Release year: the provider's, else the one in the name. Films and episodes only. */
    val year: Int? = null,
    /** The provider's rating out of 10. Films and episodes only. */
    val rating: Double? = null,
    /** What A to Z sorts on ([com.tunedplayer.app.core.TitleSort.title]); an episode's is its show's. Empty for live channels. */
    @ColumnInfo(defaultValue = "''") val sortTitle: String = "",
    /**
     * What Top rated sorts a film on: the TMDB rating its poster shows, else the provider's [rating] ([TitleRatingsSql]).
     * Worked out after each refresh and as TMDB answers; a refresh never writes it, so it isn't in [PlaylistWriter]'s columns.
     */
    val topRating: Double? = null,
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

/** A live channel as the website's Channels and guide page gets it ([com.tunedplayer.app.data.SharedChannels]). */
data class SharedChannelRow(
    val id: Long,
    val name: String,
    val groupName: String,
    val logo: String?,
    val tvgId: String?,
    val number: Int,
    val url: String,
    val region: String?,
    val adult: Boolean,
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

/** Logo and title from a first-page Room warm ([ItemDao.warmArt]), for Coil enqueue without a full list row. */
data class WarmArtRow(
    val id: Long,
    val name: String,
    val logo: String?,
)

/** Show card art from [ShowDao.warmArt]. */
data class WarmShowArtRow(
    val showKey: String,
    val showName: String,
    val logo: String?,
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
@Entity(
    tableName = "show_groups",
    indices = [
        Index("showKey"),
        Index("sortOrder"),
        Index(value = ["added", "sortOrder"]),
        Index(value = ["year", "sortOrder"]),
        Index(value = ["rating", "sortOrder"]),
        Index(value = ["sortTitle", "sortOrder"]),
    ],
)
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
    /** The newest of the show's episodes, or the provider's "last modified" ([ShowDetailsEntity]); for newest first. */
    @ColumnInfo(defaultValue = "0") val added: Long = 0,
    val year: Int? = null,
    val rating: Double? = null,
    @ColumnInfo(defaultValue = "''") val sortTitle: String = "",
)

/**
 * A show's year, rating and last change, as the Xtream API lists them (`get_series`). Its episodes in the M3U don't
 * carry them, so the Shows tab's sorts read them from here ([ShowGroups]). Replaced after each refresh that reads them.
 */
@Entity(tableName = "show_details")
data class ShowDetailsEntity(
    @PrimaryKey val showKey: String,
    val added: Long,
    val year: Int?,
    val rating: Double?,
)

/**
 * A show's own cover from the Xtream API, kept once the show has been opened. Its episodes then replace the stand-in
 * row that carried the cover, and each brings its own screenshot as its picture, so without this the show's poster
 * on the Shows tab and Downloads became one of its episodes' screenshots (Melvin, 2026-11-19: shows should use "the
 * shows main cover/poster"). Read by [ShowGroups].
 */
@Entity(tableName = "show_covers")
data class ShowCoverEntity(
    @PrimaryKey val showKey: String,
    val cover: String,
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
