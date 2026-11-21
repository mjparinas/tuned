package com.tunedplayer.app.core

import java.net.URI
import java.net.URLDecoder

/**
 * What a pasted playlist link really is. Many providers hand out their Xtream login as a `get.php` link; reading
 * the server, username and password back out of it signs in properly, so catch-up and account status work too.
 * Anything else that looks like a web address is a plain M3U link.
 */
sealed interface PlaylistLink {
    data class Xtream(
        val server: String,
        val username: String,
        val password: String,
    ) : PlaylistLink

    data class M3u(
        val url: String,
    ) : PlaylistLink

    companion object {
        private val XTREAM_PATHS = setOf("get.php", "player_api.php", "xmltv.php", "panel_api.php")

        /** Null when [text] isn't a web address at all. */
        fun parse(text: String): PlaylistLink? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val scheme = uri.scheme.orEmpty().lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            if ('.' !in host && host != "localhost" && ':' !in host) return null
            return xtream(uri, scheme, host) ?: M3u(withScheme)
        }

        /** The login inside an Xtream `get.php` (or `player_api.php`, `xmltv.php`) link, or null for any other link. */
        private fun xtream(
            uri: URI,
            scheme: String,
            host: String,
        ): Xtream? {
            val file =
                uri.rawPath
                    .orEmpty()
                    .substringAfterLast('/')
                    .lowercase()
            if (file !in XTREAM_PATHS) return null
            val query = query(uri.rawQuery.orEmpty())
            val user = query["username"].orEmpty()
            val pass = query["password"].orEmpty()
            if (user.isEmpty() || pass.isEmpty()) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val base = uri.rawPath.substringBeforeLast('/')
            return Xtream("$scheme://$host$port$base", user, pass)
        }

        private val HEADER_GUIDE = Regex("""(?:x-tvg-url|url-tvg|tvg-url)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /**
         * The guide an M3U file names in its `#EXTM3U` line (`x-tvg-url`, `url-tvg` or `tvg-url`), so a plain link
         * gets its guide without the person finding it. Several links separated by commas: the first.
         */
        fun headerGuide(firstLine: String): String? {
            if (!firstLine.trimStart().startsWith("#EXTM3U", ignoreCase = true)) return null
            val value = HEADER_GUIDE.find(firstLine)?.groupValues?.get(1) ?: return null
            return value
                .split(',')
                .map { it.trim() }
                .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }

        private fun query(raw: String): Map<String, String> = raw
            .split('&')
            .filter { '=' in it }
            .associate { pair ->
                val (key, value) = pair.split('=', limit = 2)
                URLDecoder.decode(key, "UTF-8").lowercase() to URLDecoder.decode(value, "UTF-8")
            }
    }
}
