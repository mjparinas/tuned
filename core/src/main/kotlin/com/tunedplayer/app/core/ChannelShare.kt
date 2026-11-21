package com.tunedplayer.app.core

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * The copy of a playlist's live channels and the next hours of its guide that the app keeps in the Tuned account, for
 * the website's Channels and guide page (docs/decisions/web.md › The app's copy). Melvin, 2026-11-20, chose that "the app
 * shares its list" so the page works with providers that don't let web pages read them. The page opens a channel in VLC
 * or another player, which connects to the provider itself: only this list goes through Tuned, never a stream.
 *
 * The copy is JSON, kept small: each channel is `[name, group, logo, guide id, number, link]` and each programme
 * `[start, stop, title]` in epoch seconds, by guide id. A link's login is replaced by `{login}`, `{user}` and `{pass}`,
 * which the page fills in from the playlist's login in the account, so the list itself carries no password.
 */
object ChannelShare {
    /** The guide from an hour ago, so what's on now has its start, to [GUIDE_AFTER_MS] ahead. */
    const val GUIDE_BEFORE_MS = 60 * 60_000L
    const val GUIDE_AFTER_MS = 12 * 60 * 60_000L

    /** Sent again at least this often, so the website's guide stays ahead of the clock. */
    const val REFRESH_MS = 6 * 60 * 60_000L

    /** The most the server takes, gzipped (a Vercel function's request body is at most 4.5 MB). */
    const val MAX_GZIPPED_BYTES = 4_000_000

    data class Channel(
        val name: String,
        val group: String,
        val logo: String?,
        val epgId: String?,
        val number: Int,
        val url: String,
    )

    data class Show(
        val channelId: String,
        val start: Long,
        val stop: Long,
        val title: String,
    )

    /**
     * A saved playlist's id as the website reads it (`playlistId` in devices/lib/library.mjs): the first 16 hex
     * characters of the SHA-256 of its server, username and link, one per line.
     */
    fun playlistId(
        server: String,
        username: String,
        playlistUrl: String,
    ): String = sha256("$server\n$username\n$playlistUrl").take(ID_LENGTH)

    /**
     * [url] with its login swapped for markers: `/user/pass/` (Xtream's `/live/user/pass/1.ts` and links like it)
     * becomes `/{login}/`, and `username=` and `password=` values become `{user}` and `{pass}`. Unchanged when there
     * is no login to hide.
     */
    fun hideLogin(
        url: String,
        username: String,
        password: String,
    ): String {
        if (username.length < 2 || password.isEmpty()) return url
        var out = url
        for (u in forms(username)) {
            for (p in forms(password)) out = out.replace("/$u/$p/", "/{login}/")
        }
        for (u in forms(username)) out = out.replace("username=$u&", "username={user}&").replaceEnding("username=$u", "username={user}")
        for (p in forms(password)) out = out.replace("password=$p&", "password={pass}&").replaceEnding("password=$p", "password={pass}")
        return out
    }

    /**
     * The other way from [hideLogin]: [url] with `{login}`, `{user}` and `{pass}` filled from this device's own login,
     * for a device that fills its channels from the account's copy when the provider can't be reached. The query's
     * values are URL-encoded; the path's are as the provider's own links write them.
     */
    fun showLogin(
        url: String,
        username: String,
        password: String,
    ): String = url
        .replace("/{login}/", "/$username/$password/")
        .replace("{user}", URLEncoder.encode(username, "UTF-8"))
        .replace("{pass}", URLEncoder.encode(password, "UTF-8"))

    /** What changes the channel list (not the guide), so an unchanged list isn't sent again before [REFRESH_MS]. */
    fun fingerprint(
        playlistId: String,
        channels: List<Channel>,
    ): String = sha256(
        buildString {
            append(playlistId)
            for (c in channels) {
                append('\n')
                    .append(c.name)
                    .append('\t')
                    .append(c.group)
                    .append('\t')
                    .append(c.url)
            }
        },
    )

    /** The copy as JSON, the guide limited to [channels]' guide ids from [now] less an hour to [GUIDE_AFTER_MS] ahead. */
    fun payload(
        playlistId: String,
        channels: List<Channel>,
        shows: List<Show>,
        now: Long,
        withGuide: Boolean = true,
    ): String {
        val out = StringBuilder()
        begin(out, playlistId, channels, now)
        if (withGuide) {
            val ids = guideIds(channels)
            var first = true
            for ((id, list) in shows.filter { it.channelId in ids }.groupBy { it.channelId }) {
                if (guideEntry(out, first, id, list, now)) first = false
            }
        }
        end(out)
        return out.toString()
    }

    /** The guide ids the channels name, the only ones whose programmes go in the copy. */
    fun guideIds(channels: List<Channel>): Set<String> = channels.mapNotNullTo(LinkedHashSet()) { it.epgId?.takeIf(String::isNotBlank) }

    /**
     * Writes the copy's start and its channels to [out]; then [guideEntry] once per guide id and [end]. A piece at a
     * time, so a big playlist's guide is written as it's read rather than held whole.
     */
    fun begin(
        out: Appendable,
        playlistId: String,
        channels: List<Channel>,
        now: Long,
    ) {
        out.append("{\"v\":1,\"playlist\":").append(str(playlistId))
        out.append(",\"savedAt\":").append(now.toString())
        out.append(",\"channels\":[")
        channels.forEachIndexed { i, c ->
            if (i > 0) out.append(',')
            out
                .append('[')
                .append(str(c.name))
                .append(',')
                .append(str(c.group))
                .append(',')
            out
                .append(str(c.logo.orEmpty()))
                .append(',')
                .append(str(c.epgId.orEmpty()))
                .append(',')
            out
                .append(c.number.toString())
                .append(',')
                .append(str(c.url))
                .append(']')
        }
        out.append("],\"guide\":{")
    }

    /**
     * One channel's programmes from [now] less an hour to [GUIDE_AFTER_MS] ahead, in start order, each start once.
     * [first] for the first entry written. False, writing nothing, when none fall in that window.
     */
    fun guideEntry(
        out: Appendable,
        first: Boolean,
        id: String,
        shows: List<Show>,
        now: Long,
    ): Boolean {
        val from = now - GUIDE_BEFORE_MS
        val to = now + GUIDE_AFTER_MS
        val list = shows.filter { it.stop > from && it.start < to }.sortedBy { it.start }.distinctBy { it.start }
        if (list.isEmpty()) return false
        if (!first) out.append(',')
        out.append(str(id)).append(":[")
        list.forEachIndexed { j, s ->
            if (j > 0) out.append(',')
            out
                .append('[')
                .append((s.start / MS).toString())
                .append(',')
                .append((s.stop / MS).toString())
                .append(',')
            out.append(str(s.title)).append(']')
        }
        out.append(']')
        return true
    }

    fun end(out: Appendable) {
        out.append("}}")
    }

    private fun String.replaceEnding(
        old: String,
        new: String,
    ) = if (endsWith(old)) dropLast(old.length) + new else this

    private fun forms(value: String) = setOf(value, URLEncoder.encode(value, "UTF-8"))

    /** A JSON string, with the characters JSON needs escaped. */
    internal fun str(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u").append(ch.code.toString(HEX_RADIX).padStart(UNICODE_DIGITS, '0'))
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun sha256(text: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private const val ID_LENGTH = 16
    private const val MS = 1000
    private const val HEX_RADIX = 16
    private const val UNICODE_DIGITS = 4
}
