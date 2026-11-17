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
                val (host, _, user, pass, id) = m.destructured
                return Media("$host/live/$user/$pass/$id.m3u8", HLS)
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

    private const val HLS = "application/x-mpegURL"
}
