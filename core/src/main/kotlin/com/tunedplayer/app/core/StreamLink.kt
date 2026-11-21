package com.tunedplayer.app.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A stream link and the HTTP headers its server wants. Some providers only serve a stream to a given player or page:
 * the playlist says so with `#EXTVLCOPT:http-user-agent=…` or `#EXTVLCOPT:http-referrer=…` before the link, or with
 * Kodi's form, headers after a `|` (`https://cdn/a.m3u8|User-Agent=Foo&Referer=https%3A%2F%2Fsite%2F`). Both are kept
 * in the stored link in Kodi's form, so nothing else needs a column; whatever opens a connection reads them back with
 * [split], and whatever only needs the address uses [address].
 */
object StreamLink {
    data class Parts(
        val url: String,
        val headers: Map<String, String>,
    )

    /** The headers Tuned sends; others are dropped rather than passed to every request. */
    private val allowed =
        mapOf(
            "user-agent" to USER_AGENT,
            "referer" to REFERER,
            "referrer" to REFERER,
            "origin" to ORIGIN,
            "cookie" to COOKIE,
        )

    fun split(link: String): Parts {
        val bar = link.indexOf('|')
        if (bar < 0 || '=' !in link.substring(bar)) return Parts(link, emptyMap())
        val headers = LinkedHashMap<String, String>()
        for (pair in link.substring(bar + 1).split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = allowed[pair.substring(0, eq).trim().lowercase()] ?: continue
            val value = decode(pair.substring(eq + 1)).trim()
            if (value.isNotEmpty()) headers[name] = value
        }
        return Parts(link.substring(0, bar).trim(), headers)
    }

    /** The link without any headers: what to show, cast or match on. */
    fun address(link: String): String = split(link).url

    fun join(
        url: String,
        headers: Map<String, String>,
    ): String {
        if (headers.isEmpty()) return url
        return url + "|" + headers.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8").replace("+", "%20")}" }
    }

    /** A header from an `#EXTVLCOPT:` line, or null for any other option. */
    fun vlcOption(line: String): Pair<String, String>? {
        val option = line.substringAfter(':', "")
        val eq = option.indexOf('=')
        if (eq <= 0) return null
        val name =
            when (option.substring(0, eq).trim().lowercase()) {
                "http-user-agent" -> USER_AGENT
                "http-referrer", "http-referer" -> REFERER
                "http-origin" -> ORIGIN
                else -> return null
            }
        val value = option.substring(eq + 1).trim()
        return if (value.isEmpty()) null else name to value
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        value // not encoded after all: "Mozilla/5.0 (X11; Linux)"
    }

    const val USER_AGENT = "User-Agent"
    const val REFERER = "Referer"
    const val ORIGIN = "Origin"
    const val COOKIE = "Cookie"
}
