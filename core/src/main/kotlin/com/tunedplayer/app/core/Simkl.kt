package com.tunedplayer.app.core

/**
 * Simkl: what Tuned tells Simkl while a film or episode plays, so it lands in the person's history and "watching now"
 * on every app they use. Nothing here touches the network. Matching and progress mirror [Trakt]: title and year for
 * films, show title and year with season and episode for shows; names are cleaned the same way as for ratings
 * ([Tmdb.query]).
 */
object Simkl {
    const val API = "https://api.simkl.com"
    const val APP_NAME = "tuned"
    const val SCOPE = "media:read media:write"
    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    /** What Simkl is told is playing. */
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

    /**
     * The film or episode behind an item, or null when Simkl can't match it: a live channel, or an episode without a
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

    /** How far through, as Simkl wants it: a percentage from 0 to 100, to two places. */
    fun progress(
        positionMs: Long,
        durationMs: Long,
    ): Double {
        if (durationMs <= 0) return 0.0
        val percent = positionMs.coerceIn(0, durationMs) * HUNDRED / durationMs
        return Math.round(percent * HUNDRED) / HUNDRED
    }

    /**
     * Whether a pause or stop at [progress] is worth sending. Under 1% there is nothing to save, and Trakt's same
     * floor kept empty spots out of history; Simkl's stop under 80% only saves a resume point.
     */
    fun sendsSpot(progress: Double): Boolean = progress >= MIN_SPOT_PERCENT

    /** The least progress worth a paused or stopped spot. */
    const val MIN_SPOT_PERCENT = 1.0

    /** Simkl counts a play as watched from 80%; under that, stopping only saves the spot. */
    const val WATCHED_PERCENT = 80.0

    /** The answers while waiting for the person to enter the code at simkl.com/pin. */
    enum class Poll { SIGNED_IN, WAIT, SLOW_DOWN, EXPIRED, FAILED }

    /**
     * Simkl's device poll returns HTTP 400 with an `error` string for every pending state (RFC 8628), and never a
     * deny: walking away and saying no both keep returning [authorization_pending] until the code expires.
     */
    fun poll(
        httpCode: Int,
        error: String?,
    ): Poll = when {
        httpCode == HTTP_OK -> Poll.SIGNED_IN
        httpCode == HTTP_UNAUTHORIZED -> Poll.FAILED
        error == "authorization_pending" -> Poll.WAIT
        error == "slow_down" -> Poll.SLOW_DOWN
        error == "expired_token" -> Poll.EXPIRED
        else -> Poll.FAILED
    }

    /** A token is renewed a day before it runs out, so a scrobble never fails on an expired one. */
    fun needsRefresh(
        expiresAtMs: Long,
        nowMs: Long,
    ): Boolean = nowMs >= expiresAtMs - DAY_MS

    fun deviceCodeBody(clientId: String): Map<String, String> = mapOf("client_id" to clientId, "scope" to SCOPE)

    fun deviceTokenBody(
        clientId: String,
        deviceCode: String,
    ): Map<String, String> = mapOf(
        "grant_type" to DEVICE_GRANT,
        "client_id" to clientId,
        "device_code" to deviceCode,
    )

    fun refreshBody(
        clientId: String,
        refreshToken: String,
    ): Map<String, String> = mapOf(
        "grant_type" to "refresh_token",
        "client_id" to clientId,
        "refresh_token" to refreshToken,
    )

    fun revokeBody(
        clientId: String,
        token: String,
    ): Map<String, String> = mapOf("client_id" to clientId, "token" to token)

    private const val HUNDRED = 100.0
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val HTTP_OK = 200
    private const val HTTP_UNAUTHORIZED = 401
}
