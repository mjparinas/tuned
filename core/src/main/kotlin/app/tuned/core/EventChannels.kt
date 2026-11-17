package app.tuned.core

/**
 * Sports, pay-per-view and event channels (Melvin, 2026-11-17: hiding channels must never hide "live sports, boxing,
 * UFC, football, NHL, etc., and pay-per-view … just in case there is no show at that time"). An event slot such as
 * "PPV 7" or "NHL 05" is dark between events, so a failure says nothing about the next one. These channels are never
 * hidden by a scan or a failed play, and auto-tune always considers them.
 */
object EventChannels {
    private val words =
        Regex(
            """\b(sports?|ppv|events?|ufc|wwe|aew|dazn|espn\w*|tsn\d?|sportsnet|bein|fox sports|sky sports|bt sport|tnt sports|""" +
                """nfl|nba|nhl|mlb|mls|ncaa|epl|premier league|laliga|serie a|bundesliga|ligue 1|f1|formula|motogp|nascar|indycar|""" +
                """golf|tennis|cricket|rugby|boxing|fight|fite|flo\w*|fubo|willow|super ?sport|match ?day|game ?day|""" +
                """football|soccer|hockey|baseball|basketball|wrestling|mma)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Whether the channel's name or category marks it as sport, pay-per-view or an event slot. */
    fun matches(
        name: String,
        group: String,
    ): Boolean = words.containsMatchIn("$group $name")
}
