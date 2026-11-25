package com.tunedplayer.app.data

import com.tunedplayer.app.core.Trakt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Reads Simkl's `/sync/all-items` answers into the rows [WatchedHistoryImport] matches against the playlist
 * (docs/decisions/simkl.md). The answer is `{"movies": [...]}`, `{"shows": [...]}` or `{"anime": [...]}`, or `{}` when
 * there is nothing; each item has the film or show under `movie`, `show` (or `anime` in older answers), and shows have
 * `seasons[].episodes[]` with `watched_at`. Anything unreadable in one item skips that item, never the whole list.
 */
object SimklHistory {
    private val json = Json { ignoreUnknownKeys = true }

    /** Completed films, each at its `last_watched_at` (or [nowMs] when Simkl has no time). */
    fun films(
        text: String,
        nowMs: Long,
    ): List<Trakt.WatchedFilm> = items(text).mapNotNull { item ->
        val movie = item.obj("movie") ?: return@mapNotNull null
        val title = movie.string("title") ?: return@mapNotNull null
        Trakt.WatchedFilm(title, movie.int("year"), Trakt.watchedAtMs(item.string("last_watched_at"), nowMs))
    }

    /**
     * Every watched episode of every show or anime in the answer. An anime episode uses its TVDB season and number when
     * Simkl maps one (`tvdb: {season, episode}`), since playlists number anime by TVDB seasons.
     */
    fun episodes(
        text: String,
        nowMs: Long,
    ): List<Trakt.WatchedEpisode> = buildList {
        for (item in items(text)) {
            val show = item.obj("show") ?: item.obj("anime") ?: continue
            val title = show.string("title") ?: continue
            val year = show.int("year")
            val lastWatched = item.string("last_watched_at")
            for (season in item.array("seasons")) {
                val seasonObj = season as? JsonObject ?: continue
                val seasonNumber = seasonObj.int("number")
                for (episode in seasonObj.array("episodes")) {
                    val e = episode as? JsonObject ?: continue
                    val tvdb = e.obj("tvdb")
                    val s = tvdb?.int("season") ?: seasonNumber ?: continue
                    val n = tvdb?.int("episode") ?: e.int("number") ?: continue
                    add(Trakt.WatchedEpisode(title, year, s, n, Trakt.watchedAtMs(e.string("watched_at") ?: lastWatched, nowMs)))
                }
            }
        }
    }

    /** The items of whichever list the answer holds; `{}`, `null` or an empty body is none. */
    private fun items(text: String): List<JsonObject> {
        if (text.isBlank()) return emptyList()
        val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        return LISTS.flatMap { key -> (root[key] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private val LISTS = listOf("movies", "shows", "anime")
}
