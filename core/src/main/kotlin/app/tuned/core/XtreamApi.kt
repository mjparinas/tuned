package app.tuned.core

import java.net.URLEncoder

/**
 * Reading an Xtream server through its JSON API (`player_api.php`) instead of the `get.php` M3U download. Some
 * providers turn the M3U download off, or leave series out of it, while their API answers; Tuned then reads the
 * channel, film and show lists from the API (Melvin, 2026-11-17: "fix"). The links built here are the ones `m3u_plus`
 * would list, and they end in the same stream ids, so favourites and watch history carry over between the two.
 *
 * The API lists shows without their episodes; those come one show at a time (`get_series_info`). So each show is first
 * stored as a single stand-in row whose link is `xtream-series:<id>` ([seriesStandIn]), and its episodes are fetched
 * when the show is opened.
 */
object XtreamApi {
    const val STAND_IN = "xtream-series:"

    fun action(
        server: String,
        username: String,
        password: String,
        action: String,
    ): String = XtreamUrls.account(server, username, password) + "&action=" + action

    fun seriesInfo(
        server: String,
        username: String,
        password: String,
        seriesId: String,
    ): String = action(server, username, password, "get_series_info") + "&series_id=" + enc(seriesId)

    /** The fields of one stream in an API list, already read from JSON. */
    data class Stream(
        val id: String,
        val name: String,
        val icon: String?,
        val category: String,
        val epgId: String? = null,
        val adult: Boolean = false,
        val extension: String? = null,
    )

    class Links(
        server: String,
        username: String,
        password: String,
    ) {
        private val base = XtreamUrls.normalizeServer(server)
        private val login = "${enc(username)}/${enc(password)}"

        fun live(id: String) = "$base/live/$login/$id.ts"

        fun movie(
            id: String,
            extension: String?,
        ) = "$base/movie/$login/$id.${extension.orIfBlank("mp4")}"

        fun episode(
            id: String,
            extension: String?,
        ) = "$base/series/$login/$id.${extension.orIfBlank("mp4")}"
    }

    fun live(
        links: Links,
        s: Stream,
        order: Int,
    ): PlaylistEntry = entry(s, links.live(s.id), ContentKind.LIVE, order, episode = null)

    fun movie(
        links: Links,
        s: Stream,
        order: Int,
    ): PlaylistEntry = entry(s, links.movie(s.id, s.extension), ContentKind.MOVIE, order, episode = null)

    /** A show from `get_series`, stored as one row until its episodes are fetched. */
    fun seriesStandIn(
        s: Stream,
        order: Int,
    ): PlaylistEntry = entry(s, STAND_IN + s.id, ContentKind.SERIES, order, EpisodeInfo(s.name, 0, 0))

    /** One episode from `get_series_info`, filed under [show]'s name so it joins the stand-in's show. */
    @Suppress("LongParameterList") // the fields of one API episode
    fun episode(
        links: Links,
        show: String,
        category: String,
        cover: String?,
        id: String,
        title: String?,
        season: Int,
        number: Int,
        extension: String?,
        order: Int,
    ): PlaylistEntry {
        val name = title.orIfBlank("$show S${pad(season)}E${pad(number)}")
        val s = Stream(id = id, name = name, icon = cover, category = category)
        return entry(s, links.episode(id, extension), ContentKind.SERIES, order, EpisodeInfo(show, season, number))
    }

    /** The show id of a stand-in row, or null for a real stream. */
    fun standInId(url: String): String? = url.takeIf { it.startsWith(STAND_IN) }?.removePrefix(STAND_IN)

    private fun entry(
        s: Stream,
        url: String,
        kind: ContentKind,
        order: Int,
        episode: EpisodeInfo?,
    ) = PlaylistEntry(
        name = s.name,
        url = url,
        kind = kind,
        group = s.category.ifBlank { "Uncategorized" },
        tvgId = s.epgId?.takeIf { it.isNotBlank() },
        logo = s.icon?.takeIf { it.isNotBlank() },
        region = Regions.detect(s.category, null, s.name),
        adult = s.adult || Classifier.isAdult(s.category, s.name),
        episode = episode,
        order = order,
    )

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private fun String?.orIfBlank(fallback: String) = this?.takeIf { it.isNotBlank() } ?: fallback

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
