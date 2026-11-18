package app.tuned.core

/**
 * What to hand a Chromecast for a stream. The default receiver plays HLS, DASH and MP4 but not raw MPEG-TS, and most
 * IPTV live links are TS; Xtream servers serve the same channel as HLS when the extension is `.m3u8`, so live links are
 * rewritten to that. Everything else is sent as it is, with the content type the receiver needs.
 */
object CastMedia {
    data class Media(
        val url: String,
        val mimeType: String,
    )

    private val xtreamLive = Regex("""^(https?://[^/]+)(/live)?/([^/]+)/([^/]+)/(\d+)(\.ts)?$""", RegexOption.IGNORE_CASE)

    fun forCast(
        url: String,
        live: Boolean,
    ): Media {
        val clean = url.substringBefore('?')
        if (live) {
            xtreamLive.matchEntire(clean)?.let { m ->
                val g = m.groupValues
                return Media("${g[1]}/live/${g[3]}/${g[4]}/${g[5]}.m3u8", HLS)
            }
        }
        return Media(url, mimeFor(clean))
    }

    fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "m3u8" -> HLS
        "mpd" -> "application/dash+xml"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        else -> "video/*"
    }

    /**
     * How long to wait before sending a load the TV turned down once more, or null to give up. A provider that allows
     * one connection can still count the phone's for a moment after it closes, so the first refusal gets one more try.
     */
    fun retryDelayMs(attempt: Int): Long? = if (attempt < TRIES) RETRY_DELAY_MS else null

    private const val TRIES = 2
    private const val RETRY_DELAY_MS = 3_000L
    private const val HLS = "application/x-mpegURL"
}
