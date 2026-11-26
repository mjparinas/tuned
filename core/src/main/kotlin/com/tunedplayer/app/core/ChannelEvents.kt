package com.tunedplayer.app.core

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Live events read from a channel's own name when the guide lists none for it (docs/decisions/events.md). Providers
 * rename event slots before each event ("PPV 7: UFC 312 10PM ET", "US| NHL 05: Leafs vs Canucks @ 7:00 PM EST",
 * "DAZN 3 | Canelo vs Crawford Sat 9/13 9PM ET") and follow no one format, so this takes the slot label and country off
 * the front, reads a time (with its date, weekday and zone, if any) from anywhere in the name, and keeps what is left
 * when [Events.isEvent] says it names an event. A name that says nothing ("PPV 7", "NFL 01 -- No Event --") is no event.
 *
 * Only java.time that Android 8.0 has (minSdk 26).
 */
object ChannelEvents {
    /** A sports or pay-per-view channel, for [find]. */
    data class Channel(
        val itemId: Long,
        val name: String,
    )

    /** What a channel's name says: the event and, when the name gives one, when it starts. */
    data class Named(
        val title: String,
        val start: Long?,
    )

    /** How long an event read from a name lasts, since the name gives only its start. */
    const val LENGTH_MS = 3 * 3_600_000L

    /**
     * Reads [name]. A time without a zone is in [zone] (Settings › Guide › Time zone); a time without a date is the one
     * first that hasn't ended by [now]; a weekday alone is the next such day. Throws [DateTimeException] for a date
     * that doesn't exist ("2/30"); [find] skips those.
     */
    fun parse(
        name: String,
        now: Long,
        zone: ZoneId,
    ): Named? {
        var text = " " + quality.replace(name, " ") + " "
        val first = time.find(text)
        var start: Long? = null
        if (first != null) {
            start = startOf(text, first, now, zone)
            // Remove every time and its date, weekday and zone ("7PM ET / 4PM PT").
            while (true) {
                val t = time.find(text) ?: break
                val range = chunk(text, t)
                text = text.substring(0, range.first) + " | " + text.substring(range.last + 1)
            }
        }
        text = trailingDate.replace(text, " ")
        val (title, split) = strip(text)
        if (title.length < MIN_TITLE) return null
        if (isSlot(title)) return null
        if (!Events.isEvent(title)) return null
        // A plain channel name ("Sky Sports Golf Open") only counts with a time, a slot label or a match.
        if (start == null && !split && !match.containsMatchIn(title)) return null
        return Named(Events.clean(title), start)
    }

    /**
     * The events named by [channels] whose guide lists none. Events that ended before [now] or start after [to] are
     * left out. Mistakes in one name never stop the others.
     */
    fun find(
        channels: List<Channel>,
        now: Long,
        to: Long,
        zone: ZoneId,
    ): List<Pair<Long, Named>> = channels.mapNotNull { c ->
        val named =
            try {
                parse(c.name, now, zone)
            } catch (_: DateTimeException) {
                // A date that doesn't exist ("2/30"): skip this name, keep the rest.
                null
            }
        when {
            named == null -> null
            named.start != null && (named.start + LENGTH_MS <= now || named.start >= to) -> null
            else -> c.itemId to named
        }
    }

    // A time: "10PM", "10:30 pm", "7p", "19:30", "19h30". Not a score or a number inside a word.
    private val time =
        Regex(
            """(?<![\d:./])(?:(\d{1,2})(?:[:.](\d{2}))?\s?(a\.m\.|p\.m\.|am|pm|a|p)(?![\p{L}\d])""" +
                """|([01]?\d|2[0-3])[:h]([0-5]\d)(?![\d:]))""",
            RegexOption.IGNORE_CASE,
        )

    private const val WEEKDAY =
        """(?:mon|monday|tue|tues|tuesday|wed|wednesday|thu|thur|thurs|thursday|fri|friday|sat|saturday|sun|sunday)\.?"""
    private const val MONTH =
        """(?:jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|""" +
            """nov|november|dec|december)\.?"""
    private const val DATE =
        """(?:\d{4}-\d{1,2}-\d{1,2}|\d{1,2}/\d{1,2}(?:/\d{2,4})?|\d{1,2}(?:st|nd|rd|th)?\s+$MONTH|$MONTH\s+\d{1,2}(?:st|nd|rd|th)?)"""
    private const val ZONE =
        """(?:et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|at|ast|adt|gmt|utc|bst|uk|cet|cest|aest|aedt)"""

    // What sits right before a time: its weekday and date, and "@", "at", "on", commas.
    private val before = Regex("""(?:(?<![\p{L}\d])(?:$WEEKDAY|$DATE|@|at|on)[\s,|\-]*)+$""", RegexOption.IGNORE_CASE)

    // What sits right after it: its zone, then perhaps a date ("9PM ET Sat 9/13").
    private val after =
        Regex("""^\s*(?:\(?$ZONE\)?(?![\p{L}]))?(?:[\s,]*(?:$WEEKDAY|$DATE)(?![\p{L}\d]))*""", RegexOption.IGNORE_CASE)

    private val zoneWord = Regex("""(?<![\p{L}])$ZONE(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val weekdayWord = Regex("""(?<![\p{L}])$WEEKDAY(?![\p{L}])""", RegexOption.IGNORE_CASE)
    private val isoDate = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val slashDate = Regex("""(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?""")
    private val dayMonth = Regex("""(\d{1,2})(?:st|nd|rd|th)?\s+($MONTH)""", RegexOption.IGNORE_CASE)
    private val monthDay = Regex("""($MONTH)\s+(\d{1,2})""", RegexOption.IGNORE_CASE)

    // A date left at the end of an untimed name ("UFC 312 (Feb 8)").
    private val trailingDate = Regex("""[(\[]?\s*(?:$WEEKDAY\s*)?$DATE\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)

    private val quality =
        Regex("""[(\[](?:hd|fhd|uhd|sd|4k|hevc|h265|backup|vip)[)\]]|\b(?:fhd|uhd|hevc|h265)\b""", RegexOption.IGNORE_CASE)

    // Where a slot label or country ends and the event begins.
    private val separator = Regex("""\s*(?:[:|»▎┃•]|\s[-–—]+\s|^[-–—]+\s|\s[-–—]+$)\s*""")

    // "PPV 7", "NHL 05", "ESPN+ 12", "Event #3": a slot, not an event. "UFC 312" is an event.
    private val slot =
        Regex(
            """^(?!(?:ufc|bellator|pfl|one|rizin|wwe|aew|ksw|cage warriors)\b)[\p{L}+&.' ]{1,24}?\s*#?\d{1,3}$""",
            RegexOption.IGNORE_CASE,
        )
    private val country = Regex("""^(?:[A-Z]{2,3}|USA|LATAM|VIP|4K)$""")
    private val match = Regex("""\S\s(vs?\.?|@)\s\S""", RegexOption.IGNORE_CASE)
    private val edges = Regex("""^[\s|:,@\-–—»▎┃•/]+|[\s|:,@\-–—»▎┃•/(\[]+$""")

    /** The span of [t]'s time with its weekday, date and zone around it. */
    private fun chunk(
        text: String,
        t: MatchResult,
    ): IntRange {
        val lead = before.find(text.substring(0, t.range.first))
        val from = lead?.range?.first ?: t.range.first
        val tail = after.find(text.substring(t.range.last + 1))
        val to = t.range.last + (tail?.value?.length ?: 0)
        return from..to
    }

    private fun startOf(
        text: String,
        t: MatchResult,
        now: Long,
        localZone: ZoneId,
    ): Long? {
        val g = t.groupValues
        val local =
            if (g[3].isNotEmpty()) {
                val h = g[1].toInt()
                if (h !in 1..12) return null
                val pm = g[3].startsWith("p", ignoreCase = true)
                LocalTime.of((h % 12) + if (pm) 12 else 0, g[2].ifEmpty { "0" }.toInt())
            } else {
                LocalTime.of(g[4].toInt(), g[5].toInt())
            }
        val range = chunk(text, t)
        val around = text.substring(range.first, range.last + 1)
        val zone = zoneWord.find(text.substring(t.range.last + 1, range.last + 1))?.let { zoneOf(it.value) } ?: localZone
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = dateIn(around, today)
        if (date != null) return ZonedDateTime.of(date, local, zone).toInstant().toEpochMilli()
        val weekday = weekdayWord.find(around)?.let { dayOf(it.value) }
        // The first such time that hasn't ended: a name is renamed for what's on or coming up.
        val days = if (weekday != null) (-1L..7L) else (-1L..1L)
        return days
            .map { today.plusDays(it) }
            .filter { weekday == null || it.dayOfWeek == weekday }
            .map { ZonedDateTime.of(it, local, zone).toInstant().toEpochMilli() }
            .firstOrNull { it + LENGTH_MS > now }
    }

    /** A date written in [text], in the year that puts it nearest [today]. */
    private fun dateIn(
        text: String,
        today: LocalDate,
    ): LocalDate? {
        isoDate.find(text)?.let { m ->
            return LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        val (month, day, year) =
            slashDate.find(text)?.let { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                val y =
                    m.groupValues[3]
                        .takeIf { it.isNotEmpty() }
                        ?.toInt()
                        ?.let { if (it < 100) 2000 + it else it }
                // Month first, as North American providers write it, unless that can't be a month.
                if (a > 12) Triple(b, a, y) else Triple(a, b, y)
            } ?: dayMonth.find(text)?.let { m -> Triple(monthOf(m.groupValues[2]), m.groupValues[1].toInt(), null) }
                ?: monthDay.find(text)?.let { m -> Triple(monthOf(m.groupValues[1]), m.groupValues[2].toInt(), null) }
                ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        if (year != null) return LocalDate.of(year, month, day)
        return (today.year - 1..today.year + 1)
            .mapNotNull { y -> if (day <= YearMonth.of(y, month).lengthOfMonth()) LocalDate.of(y, month, day) else null }
            .minByOrNull { abs(it.toEpochDay() - today.toEpochDay()) }
            ?: throw DateTimeException("No $month/$day in any year")
    }

    /** Takes the country and slot label off the front. Whether one came off says the name was written as "slot: event". */
    private fun strip(text: String): Pair<String, Boolean> {
        var rest = edges.replace(text.trim(), "")
        var split = false
        while (true) {
            val sep = separator.find(rest) ?: break
            if (sep.range.first == 0) {
                rest = rest.substring(sep.range.last + 1)
                continue
            }
            val left = rest.substring(0, sep.range.first).trim()
            val right = rest.substring(sep.range.last + 1).trim()
            val isSlot = isSlot(left)
            val label = isSlot || country.matches(left) || (EventChannels.matches(left, "") && !Events.isEvent(left))
            if (!label || right.length < MIN_TITLE) break
            rest = right
            // A country or "Sky Sports" off the front says nothing; "PPV 7:" says an event follows.
            split = split || isSlot
        }
        return edges.replace(emptyBrackets.replace(rest, " "), "").replace(spaces, " ") to split
    }

    private fun isSlot(text: String) = slot.matches(text) && !match.containsMatchIn(text)

    private val emptyBrackets = Regex("""[(\[][\s|,/\-]*[)\]]""")

    private val spaces = Regex("""\s{2,}""")

    private fun zoneOf(word: String): ZoneId = when (word.lowercase()) {
        "et", "est", "edt" -> ZoneId.of("America/New_York")
        "ct", "cst", "cdt" -> ZoneId.of("America/Chicago")
        "mt", "mst", "mdt" -> ZoneId.of("America/Denver")
        "pt", "pst", "pdt" -> ZoneId.of("America/Los_Angeles")
        "at", "ast", "adt" -> ZoneId.of("America/Halifax")
        "bst", "uk" -> ZoneId.of("Europe/London")
        "cet", "cest" -> ZoneId.of("Europe/Paris")
        "aest", "aedt" -> ZoneId.of("Australia/Sydney")
        else -> ZoneId.of("UTC")
    }

    private fun dayOf(word: String): DayOfWeek? {
        val w = word.lowercase().take(3)
        return DayOfWeek.entries.firstOrNull { it.name.lowercase().startsWith(w) }
    }

    private fun monthOf(word: String): Int {
        val w = word.lowercase().take(3)
        return listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(w) + 1
    }

    private const val MIN_TITLE = 4
}
