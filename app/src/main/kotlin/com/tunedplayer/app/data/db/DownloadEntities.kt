package com.tunedplayer.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

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
