package com.tunedplayer.app.data.db

/**
 * What the hiding filters need to know about a stream, worked out once when it is saved rather than on every row of
 * every list and count (Melvin, 2026-11-28: "speed up counts on the db"): whether it looks 24/7, whether a kids
 * profile keeps it, and whether it's an empty event slot. [of] works it out in Kotlin for streams saved through Room,
 * [SQL] in SQLite for streams changed in place; both follow the same rules, character for character (`ItemTagsTest`).
 */
object ItemTags {
    /** 24/7 by its category and name ([com.tunedplayer.app.core.RoundTheClock]): hidden with "247". */
    const val ROUND_THE_CLOCK = 1

    /** A kids or family category or channel ([com.tunedplayer.app.core.KidsContent]): all a kids profile keeps. */
    const val KIDS = 2

    /** A live slot with no event on ("no event", "no live event"): hidden with "placeholder". */
    const val PLACEHOLDER = 4

    private val roundTheClock =
        listOf(Regex("[^0-9]24[/ x-]7[^0-9]"), Regex("[^0-9]247[^0-9]"), Regex("[^0-9]24 / 7[^0-9]"))
    private val kidsGroups =
        listOf("kid", "child", "cartoon", "family", "junior", "disney", "nick", "baby", "animation", "boomerang")
    private val kidsNames = listOf("kid", "child", "cartoon", "junior", "disney", "nick", "baby", "boomerang")

    fun of(
        kind: Int,
        name: String,
        groupName: String,
        searchName: String,
    ): Int {
        var tags = 0
        val spaced = " " + lower("$groupName $name") + " "
        if (roundTheClock.any { it.containsMatchIn(spaced) }) tags = tags or ROUND_THE_CLOCK
        val group = lower(groupName)
        if (kidsGroups.any { it in group } || (kind == 0 && kidsNames.any { it in searchName })) tags = tags or KIDS
        val slot = " $searchName"
        if (kind == 0 && (" no event" in slot || " no live event" in slot)) tags = tags or PLACEHOLDER
        return tags
    }

    /** SQLite's lower(), which changes A to Z only. */
    private fun lower(text: String): String = buildString(text.length) { for (c in text) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }

    private const val ROUND_THE_CLOCK_SQL =
        "((' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24[/ x-]7[^0-9]*' " +
            "OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]247[^0-9]*' " +
            "OR (' ' || lower(groupName || ' ' || name) || ' ') GLOB '*[^0-9]24 / 7[^0-9]*')"

    private const val KIDS_SQL =
        "(lower(groupName) GLOB '*kid*' OR lower(groupName) GLOB '*child*' OR lower(groupName) GLOB '*cartoon*' " +
            "OR lower(groupName) GLOB '*family*' OR lower(groupName) GLOB '*junior*' OR lower(groupName) GLOB '*disney*' " +
            "OR lower(groupName) GLOB '*nick*' OR lower(groupName) GLOB '*baby*' OR lower(groupName) GLOB '*animation*' " +
            "OR lower(groupName) GLOB '*boomerang*' OR (kind = 0 AND (searchName GLOB '*kid*' OR searchName GLOB '*child*' " +
            "OR searchName GLOB '*cartoon*' OR searchName GLOB '*junior*' OR searchName GLOB '*disney*' " +
            "OR searchName GLOB '*nick*' OR searchName GLOB '*baby*' OR searchName GLOB '*boomerang*')))"

    private const val PLACEHOLDER_SQL =
        "(kind = 0 AND ((' ' || searchName) GLOB '* no event*' OR (' ' || searchName) GLOB '* no live event*'))"

    /** [of] as an SQL expression on a row of `items` (or a table with its columns), for `UPDATE … SET tags = …`. */
    const val SQL =
        "((CASE WHEN $ROUND_THE_CLOCK_SQL THEN $ROUND_THE_CLOCK ELSE 0 END) " +
            "| (CASE WHEN $KIDS_SQL THEN $KIDS ELSE 0 END) " +
            "| (CASE WHEN $PLACEHOLDER_SQL THEN $PLACEHOLDER ELSE 0 END))"
}
