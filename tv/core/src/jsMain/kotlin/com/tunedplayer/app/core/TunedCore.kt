@file:OptIn(ExperimentalJsExport::class)

package com.tunedplayer.app.core

/**
 * What the TV apps call (tv/shared wraps it in TypeScript): :core's rules with plain JavaScript types at the edge, so
 * TypeScript sees strings, numbers, booleans and arrays. Times and counts are numbers (Kotlin's Long would reach
 * JavaScript as something else), and the section is "live", "movie" or "series".
 */
@JsExport
@Suppress("LongParameterList") // one field per column a TV app keeps, as PlaylistEntry has
class Entry internal constructor(
    val name: String,
    val url: String,
    val kind: String,
    val group: String,
    val tvgId: String?,
    val logo: String?,
    val region: String?,
    val adult: Boolean,
    /** The show an episode belongs to, with its season and episode numbers (0 when unknown). */
    val showName: String?,
    val season: Int,
    val episode: Int,
    val order: Int,
    /** Epoch seconds the provider added it; 0 when it doesn't say. */
    val added: Double,
    val year: Int?,
    val rating: Double?,
    /** Days of catch-up a plain M3U channel offers; 0 for none. */
    val catchUpDays: Int,
    /** How a plain M3U channel's recordings are reached, as `M3uCatchUp.encode` writes it (mode, line break, template); null for none. */
    val catchUp: String?,
    /** Android's id for the stream (`StableId`), as decimal text: what the account's backup keeps favourites and lists by. */
    val stableId: String,
)

internal fun ContentKind.code() = when (this) {
    ContentKind.LIVE -> "live"
    ContentKind.MOVIE -> "movie"
    ContentKind.SERIES -> "series"
}

internal fun PlaylistEntry.exported() = Entry(
    name = name,
    url = url,
    kind = kind.code(),
    group = group,
    tvgId = tvgId,
    logo = logo,
    region = region,
    adult = adult,
    showName = episode?.showName,
    season = episode?.season ?: 0,
    episode = episode?.episode ?: 0,
    order = order,
    added = added.toDouble(),
    year = year,
    rating = rating,
    catchUpDays = catchUp?.days ?: 0,
    catchUp = catchUp?.encode(),
    stableId = StableId.of(this).toString(),
)

/**
 * An M3U playlist read as it downloads (Vega's fetch has no streaming body, so text arrives in pieces from progress
 * events): [feed] any piece of text, even one that stops mid-line, and get the streams whose link lines it finished;
 * [end] reads what's left. Memory stays at one line, however big the playlist.
 */
@JsExport
class M3uReader {
    private val lines = M3uLines()
    private var rest = ""

    val count: Int get() = lines.count

    fun feed(text: String): Array<Entry> {
        val out = ArrayList<Entry>()
        var start = 0
        val chunk = rest + text
        while (true) {
            val newline = chunk.indexOf('\n', start)
            if (newline < 0) break
            lines.line(chunk.substring(start, newline))?.let { out += it.exported() }
            start = newline + 1
        }
        rest = chunk.substring(start)
        return out.toTypedArray()
    }

    fun end(): Array<Entry> {
        val last = rest
        rest = ""
        return listOfNotNull(lines.line(last)?.exported()).toTypedArray()
    }
}

/** An Xtream server's links and lists (`player_api.php`), built exactly as Android builds them. */
@JsExport
class Xtream(
    server: String,
    private val username: String,
    private val password: String,
) {
    val server: String = XtreamUrls.normalizeServer(server)
    private val links = XtreamApi.Links(server, username, password)

    /** The account check: `user_info.auth` is 1 for a good login. */
    val accountUrl: String get() = XtreamUrls.account(server, username, password)

    /** The whole playlist as M3U, for a server whose API is turned off. */
    val playlistUrl: String get() = XtreamUrls.playlist(server, username, password)

    val guideUrl: String get() = XtreamUrls.guide(server, username, password)

    /** `get_live_streams`, `get_vod_streams`, `get_series`, `get_live_categories` and the rest. */
    fun actionUrl(action: String): String = XtreamApi.action(server, username, password, action)

    fun seriesInfoUrl(seriesId: String): String = XtreamApi.seriesInfo(server, username, password, seriesId)

    /** The login taken out of [text], for anything shown or logged. */
    fun redact(text: String): String = XtreamUrls.redact(text, username, password)

    /** One stream of `get_live_streams` (`live`), `get_vod_streams` (`movie`) or `get_series` (`series`). */
    @Suppress("LongParameterList") // the fields of one API stream
    fun stream(
        kind: String,
        id: String,
        name: String,
        icon: String?,
        category: String,
        epgId: String?,
        adult: Boolean,
        extension: String?,
        added: Double,
        year: Int?,
        rating: Double?,
        order: Int,
    ): Entry {
        val s = XtreamApi.Stream(id, name, icon, category, epgId, adult, extension, added.toLong(), year, rating)
        val entry =
            when (kind) {
                "movie" -> XtreamApi.movie(links, s, order)
                "series" -> XtreamApi.seriesStandIn(s, order)
                else -> XtreamApi.live(links, s, order)
            }
        return entry.exported()
    }

    /** One episode of `get_series_info`, filed under [show]. */
    @Suppress("LongParameterList") // the fields of one API episode
    fun episode(
        show: String,
        category: String,
        cover: String?,
        id: String,
        title: String?,
        season: Int,
        number: Int,
        extension: String?,
        order: Int,
    ): Entry = XtreamApi.episode(links, show, category, cover, id, title, season, number, extension, order).exported()
}

@JsExport
object TunedCore {
    /** `live`, `movie` or `series` for a link and its category, as Android sorts it. */
    fun kind(
        url: String,
        group: String,
    ): String = Classifier.kind(url, group).code()

    fun isAdult(
        group: String,
        name: String,
    ): Boolean = Classifier.isAdult(group, name)

    /** The country or language tag ("US", "UK", "EN") a stream is for, or null. */
    fun region(
        group: String,
        tvgCountry: String?,
        name: String,
        tvgLanguage: String?,
    ): String? = Regions.detect(group, tvgCountry, name, tvgLanguage)

    /** The name people read for a region tag: "Canada", "Latino". */
    fun regionName(region: String): String = Regions.displayName(region)

    /** Region tags A to Z by the name people read. */
    fun sortRegions(regions: Array<String>): Array<String> = Regions.sortedByName(regions.toList()) { it }.toTypedArray()

    /** Search text: lower case, no accents, single spaces. */
    fun searchText(text: String): String = Search.normalize(text)

    /** The show name an episode's title belongs to, as a key that ignores case and accents. */
    fun showKey(showName: String): String = Episodes.showKey(showName)

    /** Several copies of one channel ("ESPN", "ESPN HD", "ESPN FHD") share this key; null when the name has none. */
    fun copyKey(name: String): String? = ChannelCopies.key(name)

    /** How sharp a copy's picture is from its name's tags, for the HQ button; higher is sharper. */
    fun picture(name: String): Int = ChannelCopies.picture(name)

    /** Whether an Xtream account answer says the login is good: true, false, or null when it can't tell. */
    fun xtreamAccepted(body: String): Boolean? = XtreamAccount.accepted(body)

    /** Whether the account answer says every allowed connection is already in use. */
    fun xtreamConnectionsFull(body: String): Boolean? = XtreamAccount.connectionsFull(body)

    /** The half hour a time (epoch milliseconds) falls in, for the guide's ticks. */
    fun slotStart(time: Double): Double = GuideGrid.slotStart(time.toLong()).toDouble()

    /**
     * About how long a job with [done] of [total] has left at its rate since [startedAt] (epoch milliseconds), rounded
     * for words: "s:15", "m:3" or "h:1"; null when it's too early to say or nearly done.
     */
    fun remaining(
        done: Double,
        total: Double,
        startedAt: Double,
        now: Double,
    ): String? = Eta.remainingMs(done.toLong(), total.toLong(), startedAt.toLong(), now.toLong())?.let { left ->
        when (val round = Eta.round(left)) {
            is Eta.Round.Seconds -> "s:${round.n}"
            is Eta.Round.Minutes -> "m:${round.n}"
            is Eta.Round.Hours -> "h:${round.n}"
        }
    }

    /** The Xtream API's `added` or `last_modified` (epoch seconds, as text); 0 when missing. */
    fun added(text: String?): Double = TitleSort.added(text).toDouble()

    /** The provider's rating out of 10, or its five-star one doubled; null for none. */
    fun rating(
        tenBased: String?,
        fiveBased: String?,
    ): Double? = TitleSort.rating(tenBased, fiveBased)

    /** A release year from the first of [fields] that starts with one ("2021", "2021-05-04"). */
    fun year(fields: Array<String?>): Int? = fields.firstNotNullOfOrNull { TitleSort.year(it) }

    /** The year in a title's name ("Dune (2021)"), as TMDB lookups read it. */
    fun yearInName(name: String): Int? = TitleSort.yearInName(name)

    /** What A to Z sorts titles on: without tags or year, lower case, no accents. */
    fun sortTitle(name: String): String = TitleSort.title(name)

    /** The link without the Kodi-style `|User-Agent=…` headers a playlist may add. */
    fun address(link: String): String = StreamLink.address(link)

    /** The headers a link carries in Kodi's form, as name and value pairs. */
    fun headers(link: String): Array<Array<String>> = StreamLink
        .split(link)
        .headers
        .map { (name, value) -> arrayOf(name, value) }
        .toTypedArray()
}
