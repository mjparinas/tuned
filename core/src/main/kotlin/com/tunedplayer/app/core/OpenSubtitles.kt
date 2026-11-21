package com.tunedplayer.app.core

import java.net.URLEncoder
import java.util.Locale

/**
 * Subtitle search (Melvin, 2026-11-17): films and episodes whose stream has no subtitles can fetch them from
 * OpenSubtitles.com with the person's own API key (Settings › Subtitles). This builds the requests and picks the best
 * file; nothing here touches the network.
 */
object OpenSubtitles {
    const val API = "https://api.opensubtitles.com/api/v1"

    /** What to look for: a film by title and year, or an episode by its show, season and number. */
    data class Search(
        val title: String,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        /** The film's (or the show's) TMDB id when the device already knows it, which matches far better than a name. */
        val tmdbId: Long? = null,
        val language: String = DEFAULT_LANGUAGE,
    ) {
        val isEpisode: Boolean get() = season != null && episode != null
    }

    /** One subtitle file from a search answer. */
    data class Result(
        val fileId: Long,
        val release: String,
        val language: String,
        val downloads: Int,
        val hearingImpaired: Boolean = false,
        /** Machine or AI translated, which reads worse than a person's subtitles. */
        val machine: Boolean = false,
        val season: Int? = null,
        val episode: Int? = null,
    )

    /**
     * A film from its playlist name ("EN - Dune (2021) 4K"), or an episode from its show's name and numbers. The
     * caption language from Settings is used, English when it says "any".
     */
    fun search(
        name: String,
        showName: String?,
        season: Int?,
        episode: Int?,
        tmdbId: Long?,
        captionLanguage: String,
    ): Search {
        val language = language(captionLanguage)
        if (showName != null && season != null && episode != null) {
            return Search(Tmdb.query(showName).title, season = season, episode = episode, tmdbId = tmdbId, language = language)
        }
        val q = Tmdb.query(name)
        return Search(q.title, year = q.year, tmdbId = tmdbId, language = language)
    }

    /**
     * The search link. OpenSubtitles asks for the parameters in alphabetical order and in lower case, or it answers
     * with a redirect first, so they are built that way.
     */
    fun searchUrl(search: Search): String {
        val params = sortedMapOf<String, String>()
        params["languages"] = search.language
        if (search.isEpisode) {
            params["type"] = "episode"
            params["season_number"] = search.season.toString()
            params["episode_number"] = search.episode.toString()
            if (search.tmdbId != null) params["parent_tmdb_id"] = search.tmdbId.toString() else params["query"] = search.title
        } else {
            params["type"] = "movie"
            if (search.tmdbId != null) {
                params["tmdb_id"] = search.tmdbId.toString()
            } else {
                params["query"] = search.title
                search.year?.let { params["year"] = it.toString() }
            }
        }
        return "$API/subtitles?" + params.entries.joinToString("&") { (k, v) -> "$k=${enc(v.lowercase(Locale.ROOT))}" }
    }

    const val DOWNLOAD_URL = "$API/download"
    const val LOGIN_URL = "$API/login"

    /** OpenSubtitles turns away requests without a named app and version. */
    fun userAgent(version: String) = "Tuned v$version"

    /**
     * Best first: subtitles a person wrote before machine translations, then the episode asked for (searches by show
     * sometimes return its neighbours), then the most downloaded, which are usually the best timed.
     */
    fun rank(
        results: List<Result>,
        search: Search,
    ): List<Result> {
        val wanted =
            if (search.isEpisode) {
                results.filter { r ->
                    (r.season == null || r.season == search.season) && (r.episode == null || r.episode == search.episode)
                }
            } else {
                results
            }
        return wanted
            .distinctBy { it.fileId }
            .sortedWith(compareBy<Result> { it.machine }.thenBy { it.hearingImpaired }.thenByDescending { it.downloads })
    }

    /** Tuned's caption languages ("pt", "zh", or blank for any) in the codes OpenSubtitles uses. */
    fun language(code: String): String = when (val c = code.trim().lowercase(Locale.ROOT)) {
        "" -> DEFAULT_LANGUAGE
        "pt" -> "pt-pt,pt-br"
        "zh" -> "zh-cn,zh-tw"
        else -> c
    }

    /** The language to label a downloaded file with: the first one asked for, as two letters ("pt-br" is "pt"). */
    fun trackLanguage(language: String): String = language.substringBefore(',').substringBefore('-')

    /** Where a downloaded file for [itemId] is kept, so the same film needs no second download. */
    fun fileName(
        itemId: Long,
        language: String,
    ) = "$itemId-${trackLanguage(language)}.srt"

    /** The item a kept file belongs to, from [fileName]; null for anything else in the folder. */
    fun itemOf(fileName: String): Long? = fileName.takeIf { it.endsWith(".srt") }?.substringBefore('-')?.toLongOrNull()

    const val DEFAULT_LANGUAGE = "en"

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}
