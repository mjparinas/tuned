package com.tunedplayer.app.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Signing in to a Stalker (Ministra) portal with a MAC address, the way a MAG set-top box does (Melvin, 2026-11-19:
 * "do 5"). The portal has no playlist file: its channels come from its JSON API, and a channel's stream link is asked
 * for just before it plays (`create_link`), since the portal hands out a fresh token each time. So a channel is stored
 * as a `stalker://` link that keeps the portal's own command for it ([link]), and whatever opens a stream turns it into
 * the real one first. This object is the part that needs no network: addresses, the MAC, the stored links and entries.
 */
object Stalker {
    /** Marks a saved playlist as a portal, in place of a playlist link ([com.tunedplayer.app.data.Credentials]). */
    const val MARKER = "stalker:"
    const val SCHEME = "stalker://"

    /** What a MAG box says it is; portals often refuse anything else. */
    const val USER_AGENT =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    const val X_USER_AGENT = "Model: MAG250; Link: WiFi"

    /** "00:1a:79:ab:cd:ef", "001A79ABCDEF" or "00-1A-79-AB-CD-EF" as `00:1A:79:AB:CD:EF`; null when it isn't a MAC. */
    fun normalizeMac(text: String): String? {
        val hex = text.trim().filter { it != ':' && it != '-' && it != ' ' && it != '.' }
        if (hex.length != MAC_HEX || hex.any { it.lowercaseChar() !in HEX }) return null
        return hex.uppercase().chunked(2).joinToString(":")
    }

    /**
     * The portal address as typed ("host:8080/c/", "http://host/stalker_portal/c/index.html") without the page part, so
     * the API addresses can be built on it.
     */
    fun normalizePortal(text: String): String {
        var url = XtreamUrls.normalizeServer(text)
        url = url.substringBefore('?').substringBefore('#')
        // A page such as index.html is dropped, but only from the path: the host has dots too.
        val pathStart = url.indexOf('/', url.indexOf("://").let { if (it < 0) 0 else it + 3 })
        if (pathStart >= 0 && url.substringAfterLast('/').contains('.')) url = url.substringBeforeLast('/')
        url = url.trimEnd('/')
        if (url.endsWith("/c", ignoreCase = true)) url = url.dropLast(2)
        return url.trimEnd('/')
    }

    /**
     * Where the API may answer, most likely first: `stalker_portal/server/load.php` for a portal under that folder,
     * otherwise the `portal.php` most resellers use, then the other two.
     */
    fun apiCandidates(portal: String): List<String> {
        val base = normalizePortal(portal)
        val origin = origin(base)
        val list =
            if (base.contains("/stalker_portal", ignoreCase = true)) {
                val root = base.substring(0, base.indexOf("/stalker_portal", ignoreCase = true))
                listOf("$root/stalker_portal/server/load.php", "$base/portal.php")
            } else {
                listOf("$base/portal.php", "$base/server/load.php", "$origin/stalker_portal/server/load.php", "$origin/portal.php")
            }
        return list.distinct()
    }

    /** The cookie a box sends with every call. */
    fun cookie(mac: String): String = "mac=${enc(mac)}; stb_lang=en; timezone=UTC"

    /** The box's serial number, made from the MAC so each device sends the same one every time. */
    fun serial(mac: String): String = sha256(mac).take(SERIAL_LENGTH)

    fun deviceId(mac: String): String = sha256("device:$mac")

    fun signature(mac: String): String = sha256(serial(mac) + mac)

    /** One channel or film as the portal lists it, already read from JSON. */
    data class Channel(
        val id: String,
        val name: String,
        val cmd: String,
        val logo: String?,
        val genre: String,
        val xmltvId: String?,
        val adult: Boolean = false,
    )

    /**
     * A stored link: the portal's section (`itv` for live TV, `vod` for films, `series` for shows), the stream's id, the
     * command to ask a link for, and for an episode its number in the season ([series], which `create_link` takes).
     */
    data class Link(
        val type: String,
        val id: String,
        val cmd: String,
        val series: String? = null,
    )

    /** `stalker://itv/<id>?cmd=<command>`: the id is the last part of the path, which keeps ids stable ([StableId]). */
    fun link(
        type: String,
        id: String,
        cmd: String,
        series: String? = null,
    ): String = "$SCHEME$type/${enc(id)}?cmd=${enc(cmd)}" + (series?.let { "&series=${enc(it)}" } ?: "")

    fun parse(url: String): Link? {
        if (!url.startsWith(SCHEME)) return null
        val rest = url.removePrefix(SCHEME)
        val type = rest.substringBefore('/')
        val id = dec(rest.substringAfter('/', "").substringBefore('?'))
        val query = rest.substringAfter('?', "")
        val cmd = dec(param(query, "cmd").orEmpty())
        if (type.isEmpty() || cmd.isEmpty()) return null
        return Link(type, id, cmd, param(query, "series")?.let(::dec))
    }

    private fun param(
        query: String,
        name: String,
    ): String? = query.split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')

    fun isLink(url: String) = url.startsWith(SCHEME)

    /** The address in a command or a `create_link` answer, without its player prefix ("ffmpeg http://…"). */
    fun address(cmd: String): String? {
        val trimmed = cmd.trim()
        val start = trimmed.indexOf("://")
        if (start < 0) return null
        val schemeStart = trimmed.lastIndexOf(' ', start).let { if (it < 0) 0 else it + 1 }
        return trimmed.substring(schemeStart).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * A command that already names its stream, which plays when `create_link` doesn't answer. Many portals put a
     * stand-in there instead (`http://localhost/ch/123`), which never plays, so that is null.
     */
    fun direct(cmd: String): String? {
        val url = address(cmd) ?: return null
        val host =
            url
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .lowercase()
        return if (host == "localhost" || host == "127.0.0.1" || host.isEmpty()) null else url
    }

    /** Whether a channel's stream is HLS, so the player opens it as HLS before the real link is known. */
    fun isHls(cmd: String): Boolean = cmd.contains(".m3u8", ignoreCase = true)

    /** A live channel as a playlist entry, sorted into a country and an adult flag like any other. */
    fun live(
        c: Channel,
        order: Int,
    ): PlaylistEntry = PlaylistEntry(
        name = c.name,
        url = link(LIVE, c.id, c.cmd),
        kind = ContentKind.LIVE,
        group = c.genre.ifBlank { "Uncategorized" },
        tvgId = guideId(c),
        logo = c.logo?.takeIf { it.isNotBlank() },
        region = Regions.detect(c.genre, null, c.name),
        adult = c.adult || Classifier.isAdult(c.genre, c.name),
        episode = null,
        order = order,
    )

    /** The guide id a channel's programmes are filed under: its XMLTV id, so a guide link matches it, or its own id. */
    fun guideId(c: Channel): String = c.xmltvId?.trim()?.takeIf { it.isNotEmpty() } ?: "$MARKER${c.id}"

    /** A portal logo is often a path on the portal ("/stalker_portal/misc/logos/320/12.png") rather than a full address. */
    fun logo(
        portal: String,
        path: String?,
    ): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ("://" in p) return p
        val base = normalizePortal(portal)
        return if (p.startsWith("/")) origin(base) + p else "$base/$p"
    }

    /**
     * A film or show as the portal's library lists it (`get_ordered_list`, a page of about fourteen at a time). A show
     * is either in the `series` section, or a `vod` item marked `is_series` that may list its episode numbers itself.
     */
    data class VodItem(
        val id: String,
        val name: String,
        val cmd: String,
        val logo: String?,
        val isSeries: Boolean,
        val episodes: List<Int> = emptyList(),
        val adult: Boolean = false,
    )

    /** A season of a show (`get_ordered_list` with `movie_id`), with its command and episode numbers. */
    data class Season(
        val id: String,
        val name: String,
        val cmd: String,
        val episodes: List<Int>,
    )

    /** A film from the library, sorted into a country and an adult flag like any other stream. */
    fun movie(
        v: VodItem,
        category: String,
        order: Int,
    ): PlaylistEntry = PlaylistEntry(
        name = v.name,
        url = link(VOD, v.id, v.cmd),
        kind = ContentKind.MOVIE,
        group = category.ifBlank { "Uncategorized" },
        tvgId = null,
        logo = v.logo,
        region = Regions.detect(category, null, v.name),
        adult = v.adult || Classifier.isAdult(category, v.name),
        episode = null,
        order = order,
    )

    /**
     * A show, stored as one stand-in row (`stalker-show:<section>/<id>`) until it is opened, like the Xtream API's
     * shows: its seasons are a call each, so they are fetched then. A `vod` show that lists its episodes keeps them in
     * the link, for portals that have no seasons to ask for.
     */
    fun showStandIn(
        type: String,
        v: VodItem,
        category: String,
        order: Int,
    ): PlaylistEntry {
        val own = if (v.episodes.isEmpty()) "" else "?cmd=${enc(v.cmd)}&series=${v.episodes.joinToString(",")}"
        return PlaylistEntry(
            name = v.name,
            url = "$SHOW$type/${enc(v.id)}$own",
            kind = ContentKind.SERIES,
            group = category.ifBlank { "Uncategorized" },
            tvgId = null,
            logo = v.logo,
            region = Regions.detect(category, null, v.name),
            adult = v.adult || Classifier.isAdult(category, v.name),
            episode = EpisodeInfo(v.name, 0, 0),
            order = order,
        )
    }

    /** A show's stand-in, read back: its section, id, and the episodes a `vod` show listed itself. */
    data class StandIn(
        val type: String,
        val id: String,
        val cmd: String?,
        val episodes: List<Int>,
    )

    fun standIn(url: String): StandIn? {
        if (!url.startsWith(SHOW)) return null
        val rest = url.removePrefix(SHOW)
        val type = rest.substringBefore('/')
        val id = dec(rest.substringAfter('/', "").substringBefore('?'))
        if (type.isEmpty() || id.isEmpty()) return null
        val query = rest.substringAfter('?', "")
        val episodes = param(query, "series")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        return StandIn(type, id, param(query, "cmd")?.let(::dec), episodes)
    }

    /** One episode: the season's command with its number, filed under [show]'s name so it joins the stand-in's show. */
    @Suppress("LongParameterList") // the fields of one episode
    fun episode(
        type: String,
        showId: String,
        show: String,
        category: String,
        cover: String?,
        seasonCmd: String,
        season: Int,
        number: Int,
        order: Int,
    ): PlaylistEntry = PlaylistEntry(
        name = "$show S${pad(season)}E${pad(number)}",
        url = link(type, "$showId.$season.$number", seasonCmd, number.toString()),
        kind = ContentKind.SERIES,
        group = category,
        tvgId = null,
        logo = cover,
        region = Regions.detect(category, null, show),
        adult = Classifier.isAdult(category, show),
        episode = EpisodeInfo(show, season, number),
        order = order,
    )

    /** A season's number from its name ("Season 2", "S02", "Сезон 3"), or its place in the list. */
    fun seasonNumber(
        name: String,
        index: Int,
    ): Int = Regex("\\d+")
        .findAll(name)
        .lastOrNull()
        ?.value
        ?.toIntOrNull()
        ?.takeIf { it in 1..MAX_SEASON } ?: (index + 1)

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    /** A guide time: portals send seconds, some milliseconds. */
    fun millis(value: Long): Long = if (value < MILLIS_FROM) value * 1000 else value

    private fun origin(url: String): String {
        val scheme = url.substringBefore("://")
        return scheme + "://" + url.substringAfter("://").substringBefore('/')
    }

    private fun sha256(text: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02X".format(it) }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun dec(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }

    const val LIVE = "itv"
    const val VOD = "vod"
    const val SERIES = "series"
    const val SHOW = "stalker-show:"
    private const val MAX_SEASON = 200
    private const val HEX = "0123456789abcdef"
    private const val MAC_HEX = 12
    private const val SERIAL_LENGTH = 13

    /** Anything this large is already milliseconds (the year 2001 in milliseconds is far below it in seconds). */
    private const val MILLIS_FROM = 100_000_000_000L
}
