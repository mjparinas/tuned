package com.tunedplayer.app.core

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Trakt (from the app audit, item 14): what Tuned tells Trakt while a film or episode plays, so it lands in the person's
 * history and "up next" on every app they use. Nothing here touches the network.
 *
 * Trakt matches a film by title and year, and an episode by its show's title and year with the season and number, so
 * no TMDB key is needed: names are cleaned of the provider's tags the same way the ratings are ([Tmdb.query]).
 */
object Trakt {
    const val API = "https://api.trakt.tv"
    const val API_VERSION = "2"

    /** What Trakt is told is playing. */
    sealed interface Target {
        data class Movie(
            val title: String,
            val year: Int?,
        ) : Target

        data class Episode(
            val show: String,
            val year: Int?,
            val season: Int,
            val number: Int,
        ) : Target
    }

    /** A film from Trakt's watched list, for importing into Tuned's own history. */
    data class WatchedFilm(
        val title: String,
        val year: Int?,
        val watchedAtMs: Long,
    )

    /** An episode from Trakt's watched shows (with season progress), for importing into Tuned's own history. */
    data class WatchedEpisode(
        val show: String,
        val year: Int?,
        val season: Int,
        val number: Int,
        val watchedAtMs: Long,
    )

    /** A film in the playlist, cleaned the same way scrobbling cleans names. */
    data class LocalFilm(
        val id: Long,
        val title: String,
        val year: Int?,
    )

    /** An episode in the playlist, cleaned the same way scrobbling cleans show names. */
    data class LocalEpisode(
        val id: Long,
        val show: String,
        val year: Int?,
        val season: Int,
        val number: Int,
    )

    /** A playlist item id and when Trakt last counted it watched. */
    data class Match(
        val itemId: Long,
        val watchedAtMs: Long,
    )

    /** A film row from the playlist, ready to match against Trakt. */
    fun localFilm(
        id: Long,
        name: String,
        year: Int?,
    ): LocalFilm {
        val query = Tmdb.query(name)
        return LocalFilm(id, cleanKey(query.title), year ?: query.year)
    }

    /** An episode row from the playlist, ready to match against Trakt. */
    fun localEpisode(
        id: Long,
        showName: String,
        year: Int?,
        season: Int,
        number: Int,
    ): LocalEpisode {
        val query = Tmdb.query(showName)
        return LocalEpisode(id, cleanKey(query.title), year ?: query.year, season, number)
    }

    /**
     * Playlist films that match [watched]: same cleaned title, and the same year when both sides have one. Every
     * matching copy is kept (a film can sit in more than one group).
     */
    fun matchFilms(
        local: List<LocalFilm>,
        watched: List<WatchedFilm>,
    ): List<Match> {
        if (local.isEmpty() || watched.isEmpty()) return emptyList()
        val byTitle = local.groupBy { it.title }
        return buildList {
            for (film in watched) {
                val key = cleanKey(film.title)
                val candidates = byTitle[key] ?: continue
                for (row in candidates) {
                    if (yearsMatch(row.year, film.year)) add(Match(row.id, film.watchedAtMs))
                }
            }
        }
    }

    /**
     * Playlist episodes that match [watched]: same cleaned show title, season and number, and the same year when both
     * sides have one. Every matching copy is kept.
     */
    fun matchEpisodes(
        local: List<LocalEpisode>,
        watched: List<WatchedEpisode>,
    ): List<Match> {
        if (local.isEmpty() || watched.isEmpty()) return emptyList()
        val byKey = local.groupBy { episodeKey(it.show, it.season, it.number) }
        return buildList {
            for (episode in watched) {
                val key = episodeKey(cleanKey(episode.show), episode.season, episode.number)
                val candidates = byKey[key] ?: continue
                for (row in candidates) {
                    if (yearsMatch(row.year, episode.year)) add(Match(row.id, episode.watchedAtMs))
                }
            }
        }
    }

    /** Years agree when either side left it out (providers often do) or both say the same year. */
    fun yearsMatch(
        a: Int?,
        b: Int?,
    ): Boolean = a == null || b == null || a == b

    /**
     * Trakt's `last_watched_at` (ISO-8601) as epoch millis, or [fallbackMs] when missing or unreadable so a match still
     * lands in the history.
     */
    fun watchedAtMs(
        text: String?,
        fallbackMs: Long,
    ): Long {
        if (text.isNullOrBlank()) return fallbackMs
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeParseException) {
            fallbackMs
        }
    }

    /** Films page size for `/sync/watched/movies` (Trakt's usual maximum). */
    const val WATCHED_PAGE = 250

    /** Shows page size for `/sync/watched/shows?extended=progress` (Trakt caps progress pages at 100). */
    const val WATCHED_PROGRESS_PAGE = 100

    private fun cleanKey(title: String): String = title.trim().lowercase(Locale.ROOT)

    private fun episodeKey(
        show: String,
        season: Int,
        number: Int,
    ): String = "$show\t$season\t$number"

    /**
     * The film or episode behind an item, or null when Trakt can't match it: a live channel, or an episode without a
     * season and number.
     */
    fun target(
        kind: ContentKind,
        name: String,
        showName: String?,
        season: Int?,
        episode: Int?,
    ): Target? = when (kind) {
        ContentKind.MOVIE -> {
            Tmdb.query(name).let { Target.Movie(it.title, it.year) }
        }

        ContentKind.SERIES -> {
            if (showName == null || season == null || episode == null) {
                null
            } else {
                Tmdb.query(showName).let { Target.Episode(it.title, it.year, season, episode) }
            }
        }

        ContentKind.LIVE -> {
            null
        }
    }

    /** How far through, as Trakt wants it: a percentage from 0 to 100, to two places. */
    fun progress(
        positionMs: Long,
        durationMs: Long,
    ): Double {
        if (durationMs <= 0) return 0.0
        val percent = positionMs.coerceIn(0, durationMs) * HUNDRED / durationMs
        return Math.round(percent * HUNDRED) / HUNDRED
    }

    /**
     * Whether a pause or stop at [progress] is worth sending. Trakt turns down a spot under 1% ("progress_too_low",
     * HTTP 422; 2026-11-18 logs showed it at 0% while a cast had the video and at 0.44%), and there is nothing to save
     * there anyway.
     */
    fun sendsSpot(progress: Double): Boolean = progress >= MIN_SPOT_PERCENT

    /** The least progress Trakt keeps a paused or stopped spot for. */
    const val MIN_SPOT_PERCENT = 1.0

    /** Trakt counts a play as watched from 80%; under that, stopping only saves the spot. */
    const val WATCHED_PERCENT = 80.0

    /** The answers while waiting for the person to enter the code at trakt.tv/activate. */
    enum class Poll { SIGNED_IN, WAIT, SLOW_DOWN, EXPIRED, DENIED, FAILED }

    fun poll(httpCode: Int): Poll = when (httpCode) {
        HTTP_OK -> Poll.SIGNED_IN
        HTTP_PENDING -> Poll.WAIT
        HTTP_SLOW_DOWN -> Poll.SLOW_DOWN
        HTTP_NOT_FOUND, HTTP_EXPIRED -> Poll.EXPIRED
        HTTP_DENIED, HTTP_USED -> Poll.DENIED
        else -> Poll.FAILED
    }

    /** A token is renewed a day before it runs out, so a scrobble never fails on an expired one. */
    fun needsRefresh(
        expiresAtMs: Long,
        nowMs: Long,
    ): Boolean = nowMs >= expiresAtMs - DAY_MS

    private const val HUNDRED = 100.0
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val HTTP_OK = 200
    private const val HTTP_PENDING = 400
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_USED = 409
    private const val HTTP_EXPIRED = 410
    private const val HTTP_DENIED = 418
    private const val HTTP_SLOW_DOWN = 429

    /**
     * The sign-in bodies. Trakt no longer issues a client secret (apps sign in with PKCE or a device code), so every
     * body carries the client id alone; sending an empty secret would be refused.
     */
    fun deviceTokenBody(
        clientId: String,
        deviceCode: String,
    ): Map<String, String> = mapOf("code" to deviceCode, "client_id" to clientId)

    fun refreshBody(
        clientId: String,
        refreshToken: String,
    ): Map<String, String> = mapOf("refresh_token" to refreshToken, "client_id" to clientId, "grant_type" to "refresh_token")

    fun revokeBody(
        clientId: String,
        token: String,
    ): Map<String, String> = mapOf("token" to token, "client_id" to clientId)
}
