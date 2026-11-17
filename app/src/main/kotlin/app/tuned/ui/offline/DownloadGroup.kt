package app.tuned.ui.offline

import app.tuned.data.db.DownloadRow

/** One heading on the Downloads page: a show with its seasons, or the films. */
sealed interface DownloadGroup {
    val key: String

    data class Show(
        override val key: String,
        val name: String,
        val seasons: List<Season>,
    ) : DownloadGroup

    data class Season(
        val number: Int?,
        val rows: List<DownloadRow>,
    )

    data class Movies(
        val rows: List<DownloadRow>,
    ) : DownloadGroup {
        override val key = "movies"
    }
}

/**
 * Sorts downloads into shows (then seasons, then episodes in order) and films, instead of one long list (Melvin,
 * 2026-11-17). With [queueOrder] the groups follow the order things will download, so the one downloading now is at
 * the top; otherwise the most recently saved group comes first. An episode whose show has left the playlist is
 * listed with the films, since there's nothing left to group it by.
 */
fun groupDownloads(
    rows: List<DownloadRow>,
    queueOrder: Boolean,
): List<DownloadGroup> {
    val (episodes, movies) = rows.partition { it.showKey != null }
    val byAdded = compareBy<DownloadRow> { it.download.addedAt }
    val within = if (queueOrder) byAdded else byAdded.reversed()
    val byEpisode = compareBy<DownloadRow, Int?>(nullsLast()) { it.episode }
    val groups =
        episodes.groupBy { requireNotNull(it.showKey) }.map { (key, list) ->
            val seasons =
                list
                    .groupBy { it.season }
                    .toSortedMap(nullsLast(naturalOrder()))
                    .map { (season, eps) ->
                        DownloadGroup.Season(season, eps.sortedWith(byEpisode.then(byAdded)))
                    }
            DownloadGroup.Show("show:$key", list.firstNotNullOfOrNull { it.showName } ?: key, seasons) to list
        } + listOfNotNull(movies.takeIf { it.isNotEmpty() }?.let { DownloadGroup.Movies(it.sortedWith(within)) to it })
    val order = { list: List<DownloadRow> -> if (queueOrder) list.minOf { it.download.addedAt } else -list.maxOf { it.download.addedAt } }
    return groups.sortedBy { (_, list) -> order(list) }.map { it.first }
}
