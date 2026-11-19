package com.tunedplayer.app.core

import kotlin.math.abs

/**
 * Live Events (Melvin, 2026-11-17, from the app audit). A list of today's games, fights and pay-per-views built from
 * the guide, so a live event is one tap away instead of a search through dozens of "PPV 1 … PPV 50" channels. Each
 * event lists every channel showing it, and Tune hands the event's title to auto-tune.
 */
object Events {
    /** One programme in the guide, on a live channel. */
    data class Row(
        val itemId: Long,
        val channelName: String,
        val group: String,
        val title: String,
        val start: Long,
        val stop: Long,
    )

    data class Event(
        val key: String,
        val title: String,
        val start: Long,
        val stop: Long,
        /** The channels showing it, in the order the guide listed them. */
        val channels: List<Long>,
    ) {
        fun liveAt(now: Long): Boolean = start <= now && now < stop
    }

    /** A title that names a match ("Leafs vs. Canucks", "Arsenal v Chelsea", "Lakers @ Celtics"). */
    private val match = Regex("""\S.*\s(vs?\.?|@|at)\s.*\S""", RegexOption.IGNORE_CASE)

    /** A title that names an event ("UFC 312", "Fight Night", "Grand Prix", "Super Bowl", "Final"). */
    private val eventWords =
        Regex(
            """\b(ufc \d+|ufc fight night|fight night|ppv|pay[- ]per[- ]view|grand prix|gp|super bowl|stanley cup|world series|""" +
                """world cup|final|finals|semi-?final|playoffs?|championship|open|derby|classic|bowl|wrestlemania|""" +
                """main card|prelims|undercard|title fight|boxing)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Titles that fill a sports channel between events. */
    private val filler =
        Regex(
            """\b(highlights|replay|re-air|encore|rewind|best of|classics|preview|review|magazine|news|centre|center|tonight|""" +
                """daily|show|podcast|talk|countdown|paid programming|off air|no event|to be announced|tba|coming soon|""" +
                """infomercial|documentary|30 for 30|top \d+)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Markers that say nothing about what the event is. */
    private val markers = Regex("""(?i)^\s*(live|en vivo|direct)\s*[:\-|]\s*|\s*[(\[](live|l|r|hd|fhd|4k|new)[)\]]\s*""")

    /** Whether a channel is sport or pay-per-view: the same list that keeps such channels from being hidden. */
    fun sportyChannel(
        name: String,
        group: String,
    ): Boolean = EventChannels.matches(name, group)

    /** Whether [title] on a sports channel is an event worth listing, rather than a show or a replay. */
    fun isEvent(title: String): Boolean {
        val clean = clean(title)
        if (clean.length < MIN_TITLE) return false
        if (filler.containsMatchIn(clean)) return false
        return match.containsMatchIn(clean) || eventWords.containsMatchIn(clean)
    }

    /** The title without "LIVE:", "(L)" and similar, as shown. */
    fun clean(title: String): String = markers.replace(title, " ").trim().replace(Regex("""\s{2,}"""), " ")

    /** What the same event's listings on different channels have in common. */
    fun key(title: String): String = Search.normalize(clean(title))

    /**
     * Groups programmes into events: the same title on several channels, starting within [SAME_START_MS] of each other,
     * is one event. Events that ended before [now] are left out; the rest come live first, then by start time.
     */
    fun group(
        rows: List<Row>,
        now: Long,
    ): List<Event> {
        val events = mutableListOf<Event>()
        val open = mutableMapOf<String, MutableList<Int>>()
        for (row in rows.sortedBy { it.start }) {
            if (row.stop <= now || !isEvent(row.title)) continue
            val key = key(row.title)
            val candidates = open.getOrPut(key) { mutableListOf() }
            val at = candidates.firstOrNull { abs(events[it].start - row.start) <= SAME_START_MS }
            if (at == null) {
                candidates += events.size
                events += Event(key, clean(row.title), row.start, row.stop, listOf(row.itemId))
            } else {
                val e = events[at]
                if (row.itemId !in e.channels) {
                    events[at] = e.copy(stop = maxOf(e.stop, row.stop), channels = e.channels + row.itemId)
                }
            }
        }
        return events.sortedWith(compareByDescending<Event> { it.liveAt(now) }.thenBy { it.start })
    }

    /** Whether the event is about something the person follows ("UFC", "Canucks", "Premier League"). */
    fun follows(
        event: Event,
        followed: Collection<String>,
    ): Boolean {
        val words = " ${event.key} "
        return followed.any { f ->
            val k = Search.normalize(f)
            k.isNotEmpty() && " $k " in words
        }
    }

    /**
     * The events whose alerts go when [name] is unfollowed: those it matched that nothing still [followed] covers.
     * Following "Canucks" and "NHL" and dropping "Canucks" keeps the alert for "NHL: Canucks vs Oilers".
     */
    fun noLongerFollowed(
        events: List<Event>,
        name: String,
        followed: Collection<String>,
    ): List<Event> = events.filter { follows(it, listOf(name)) && !follows(it, followed) }

    private const val MIN_TITLE = 3
    const val SAME_START_MS = 30 * 60_000L
}
