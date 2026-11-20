package com.tunedplayer.app.ui.offline

import com.tunedplayer.app.core.Titles
import com.tunedplayer.app.data.db.DownloadEntity
import com.tunedplayer.app.data.db.DownloadRow

/** A show on the Downloads page: listed once with its poster, opening onto its seasons. */
data class ShowDownloads(
    val showKey: String,
    val name: String,
    /** The show's name as the playlist has it, which TMDB is asked by (and [com.tunedplayer.app.data.SavedArtwork] saved under). */
    val lookupName: String,
    val poster: String?,
    val seasons: List<SeasonDownloads>,
) {
    val rows: List<DownloadRow> get() = seasons.flatMap { it.rows }

    /** How many of its episodes are saved, from 0 to 1, for the bar under its poster while some are on their way. */
    val savedFraction: Float get() = rows.count { it.download.state == DownloadEntity.DONE }.toFloat() / rows.size.coerceAtLeast(1)
}

/** A season of a show, opening onto its episodes in order. */
data class SeasonDownloads(
    val number: Int?,
    val rows: List<DownloadRow>,
) {
    /** The first episode's picture stands in for the season. */
    val picture: String? get() = rows.firstNotNullOfOrNull { it.logo }
}

/**
 * The Downloads page: each show once (then its seasons, then its episodes) and the films, instead of one long list
 * (Melvin, 2026-11-17). An episode whose show has left the playlist is listed with the films, since there's nothing
 * left to group it by.
 */
data class DownloadLibrary(
    val shows: List<ShowDownloads>,
    val movies: List<DownloadRow>,
) {
    fun show(key: String): ShowDownloads? = shows.firstOrNull { it.showKey == key }

    companion object {
        /**
         * [posters] are the shows' own TMDB posters by show key, as saved with their episodes: the show's main cover
         * (Melvin, 2026-11-19: "Shows on the download page should use the shows main cover/poster"). Without one, the
         * playlist's show picture stands in, which for an Xtream show already opened can be one of its episodes'
         * screenshots, so it comes second.
         *
         * Shows with something still downloading come first, in the order they'll download, then the most recently
         * saved. Films on their way come first in queue order, then saved ones newest first.
         */
        fun of(
            rows: List<DownloadRow>,
            posters: Map<String, String> = emptyMap(),
        ): DownloadLibrary {
            val (episodes, movies) = rows.partition { it.showKey != null }
            val byAdded = compareBy<DownloadRow> { it.download.addedAt }
            val byEpisode = compareBy<DownloadRow, Int?>(nullsLast()) { it.episode }.then(byAdded)
            val shows =
                episodes
                    .groupBy { requireNotNull(it.showKey) }
                    .map { (key, list) ->
                        val seasons =
                            list
                                .groupBy { it.season }
                                .toSortedMap(nullsLast(naturalOrder()))
                                .map { (season, eps) -> SeasonDownloads(season, eps.sortedWith(byEpisode)) }
                        val lookupName = list.firstNotNullOfOrNull { it.showName } ?: key
                        ShowDownloads(
                            showKey = key,
                            // Without the provider's language tag: "The Simpsons", not "EN - The Simpsons" (Melvin, 2026-11-18).
                            name = Titles.display(lookupName),
                            lookupName = lookupName,
                            poster =
                                posters[key]
                                    ?: list.firstNotNullOfOrNull { it.showLogo }
                                    ?: seasons.firstNotNullOfOrNull { it.picture },
                            seasons = seasons,
                        )
                    }.sortedWith(compareBy<ShowDownloads> { order(it.rows) })
            val (saved, coming) = movies.partition { it.download.state == DownloadEntity.DONE }
            return DownloadLibrary(shows, coming.sortedWith(byAdded) + saved.sortedWith(byAdded.reversed()))
        }

        /** Anything still to download sorts first, earliest queued first; then newest saved first. */
        private fun order(rows: List<DownloadRow>): Long {
            val coming = rows.filter { it.download.state != DownloadEntity.DONE }
            return if (coming.isNotEmpty()) {
                coming.minOf { it.download.addedAt } - Long.MAX_VALUE / 2
            } else {
                Long.MAX_VALUE / 2 - rows.maxOf { it.download.addedAt }
            }
        }
    }
}
