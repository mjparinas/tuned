package com.tunedplayer.app.core

/** The three sections of the app. Stored as [code] in the database, so codes never change. */
enum class ContentKind(
    val code: Int,
) {
    LIVE(0),
    MOVIE(1),
    SERIES(2),
    ;

    companion object {
        fun fromCode(code: Int): ContentKind = entries.firstOrNull { it.code == code } ?: LIVE
    }
}

/** Season and episode of a series entry, read from its title ("Show Name S01 E02"). */
data class EpisodeInfo(
    val showName: String,
    val season: Int,
    val episode: Int,
)

/** One stream from an M3U playlist, already sorted into a section, a category and a region. */
data class PlaylistEntry(
    val name: String,
    val url: String,
    val kind: ContentKind,
    val group: String,
    val tvgId: String?,
    val logo: String?,
    val region: String?,
    val adult: Boolean,
    val episode: EpisodeInfo?,
    val order: Int,
    /** A plain M3U channel's catch-up, when its playlist names one. */
    val catchUp: M3uCatchUp? = null,
)

/** One programme from an XMLTV guide. Times are epoch milliseconds. */
data class Programme(
    val channelId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)
