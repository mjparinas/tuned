package com.tunedplayer.app.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Plex, Jellyfin and Emby as playlist sources (Melvin, 2026-11-22). Each is a home media server rather than an IPTV
 * reseller: the app talks to its JSON API for films, shows and Live TV, and asks for a stream link just before play
 * (the token lives in the encrypted login, not in every row). This object is the part that needs no network: markers,
 * stored links and playlist entries. The HTTP clients live in `:data` ([com.tunedplayer.app.data.EmbyServer],
 * [com.tunedplayer.app.data.PlexServer]).
 */
object MediaServer {
    enum class Product(
        val marker: String,
        val scheme: String,
        val showPrefix: String,
        val label: String,
    ) {
        Plex("plex:", "plex://", "plex-show:", "Plex"),
        Jellyfin("jellyfin:", "jellyfin://", "jellyfin-show:", "Jellyfin"),
        Emby("emby:", "emby://", "emby-show:", "Emby"),
        ;

        companion object {
            fun ofMarker(playlistUrl: String): Product? = entries.firstOrNull { it.marker == playlistUrl }

            fun ofLink(url: String): Product? = entries.firstOrNull { url.startsWith(it.scheme) || url.startsWith(it.showPrefix) }
        }
    }

    /** A library item already read from JSON. */
    data class Item(
        val id: String,
        val name: String,
        val logo: String?,
        val group: String,
        val year: Int? = null,
        val adult: Boolean = false,
    )

    /** A live channel from the server's tuner / Live TV API. */
    data class Channel(
        val id: String,
        val name: String,
        val logo: String?,
        val group: String,
        val guideId: String?,
        val number: Int? = null,
    )

    /** An episode of a show, fetched when the show is opened. */
    data class Episode(
        val id: String,
        val name: String,
        val season: Int,
        val number: Int,
        val logo: String?,
    )

    data class Link(
        val product: Product,
        val id: String,
    )

    data class StandIn(
        val product: Product,
        val id: String,
    )

    fun isLink(url: String) = Product.entries.any { url.startsWith(it.scheme) }

    fun isShow(url: String) = Product.entries.any { url.startsWith(it.showPrefix) }

    fun link(
        product: Product,
        id: String,
    ): String = "${product.scheme}item/${enc(id)}"

    fun parse(url: String): Link? {
        val product = Product.entries.firstOrNull { url.startsWith(it.scheme) } ?: return null
        val rest = url.removePrefix(product.scheme)
        if (!rest.startsWith("item/")) return null
        val id = dec(rest.removePrefix("item/").substringBefore('?'))
        return if (id.isEmpty()) null else Link(product, id)
    }

    fun showStandIn(
        product: Product,
        item: Item,
        order: Int,
    ): PlaylistEntry = PlaylistEntry(
        name = item.name,
        url = "${product.showPrefix}${enc(item.id)}",
        kind = ContentKind.SERIES,
        group = item.group.ifBlank { "Uncategorized" },
        tvgId = null,
        logo = item.logo,
        region = Regions.detect(item.group, null, item.name),
        adult = item.adult || Classifier.isAdult(item.group, item.name),
        episode = EpisodeInfo(item.name, 0, 0),
        order = order,
        year = item.year,
    )

    fun standIn(url: String): StandIn? {
        val product = Product.entries.firstOrNull { url.startsWith(it.showPrefix) } ?: return null
        val id = dec(url.removePrefix(product.showPrefix).substringBefore('?'))
        return if (id.isEmpty()) null else StandIn(product, id)
    }

    fun movie(
        product: Product,
        item: Item,
        order: Int,
    ): PlaylistEntry = PlaylistEntry(
        name = item.name,
        url = link(product, item.id),
        kind = ContentKind.MOVIE,
        group = item.group.ifBlank { "Uncategorized" },
        tvgId = null,
        logo = item.logo,
        region = Regions.detect(item.group, null, item.name),
        adult = item.adult || Classifier.isAdult(item.group, item.name),
        episode = null,
        order = order,
        year = item.year,
    )

    fun live(
        product: Product,
        channel: Channel,
        order: Int,
    ): PlaylistEntry = PlaylistEntry(
        name = channel.name,
        url = link(product, channel.id),
        kind = ContentKind.LIVE,
        group = channel.group.ifBlank { "Live TV" },
        tvgId = channel.guideId?.trim()?.takeIf { it.isNotEmpty() } ?: "${product.marker}${channel.id}",
        logo = channel.logo,
        region = Regions.detect(channel.group, null, channel.name),
        adult = Classifier.isAdult(channel.group, channel.name),
        episode = null,
        order = order,
    )

    fun episode(
        product: Product,
        show: String,
        category: String,
        cover: String?,
        ep: Episode,
        order: Int,
    ): PlaylistEntry = PlaylistEntry(
        name = ep.name.ifBlank { "$show S${pad(ep.season)}E${pad(ep.number)}" },
        url = link(product, ep.id),
        kind = ContentKind.SERIES,
        group = category,
        tvgId = null,
        logo = ep.logo ?: cover,
        region = Regions.detect(category, null, show),
        adult = Classifier.isAdult(category, show),
        episode = EpisodeInfo(show, ep.season, ep.number),
        order = order,
    )

    /** Server address as typed, with a scheme and no trailing slash. */
    fun normalizeServer(text: String): String = XtreamUrls.normalizeServer(text)

    fun logo(
        server: String,
        path: String?,
    ): String? {
        val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if ("://" in p) return p
        val base = normalizeServer(server)
        return if (p.startsWith("/")) base + p else "$base/$p"
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun dec(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }
}
