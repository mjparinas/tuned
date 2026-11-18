package app.tuned.core

/**
 * Live TV's Sports and PPV quick filters (Melvin, 2026-11-18: "filter by country or language or PPV or sports"). A
 * channel is in one when its name or its category has one of the topic's words. Names are matched by the full-text
 * index ([fullText]), so a playlist of 40,000 channels is narrowed by one index lookup rather than a pattern per row;
 * categories, a few hundred at most, are matched here ([matches]) by the same rules.
 *
 * A word ending in `*` also matches longer words that start with it ("sport*" finds "Sports" and "Sportsnet"); one
 * without matches only itself, so "NBA" isn't "Nbaby" and "F1" isn't "F12". A word with a space is a phrase.
 */
enum class LiveTopic(
    private val words: List<String>,
) {
    SPORTS(
        listOf(
            "sport*",
            "eurosport*",
            "supersport*",
            "skysport*",
            "btsport*",
            "foxsport*",
            "beinsport*",
            "tntsport*",
            "football*",
            "soccer*",
            "hockey*",
            "baseball*",
            "basketball*",
            "wrestling*",
            "boxing*",
            "cricket*",
            "rugby*",
            "tennis*",
            "golf*",
            "nascar*",
            "motogp*",
            "indycar*",
            "bundesliga*",
            "laliga*",
            "la liga",
            "premier league",
            "serie a",
            "ligue 1",
            "formula 1",
            "matchday*",
            "match day",
            "gameday*",
            "game day",
            "espn*",
            "dazn*",
            "fubo*",
            "fight*",
            "ufc*",
            "tsn*",
            "bein",
            "wwe",
            "aew",
            "nfl",
            "nba",
            "nhl",
            "mlb",
            "mls",
            "ncaa",
            "epl",
            "f1",
            "mma",
            "willow",
        ),
    ),
    PPV(listOf("ppv*", "pay per view", "event*")),
    ;

    /** The full-text query on `items_fts` (normalized names) for channels named with one of the words. */
    val fullText: String = words.joinToString(" OR ") { if (' ' in it) "\"$it\"" else it }

    /** Whether [text] (a category such as "US| NFL Sunday Ticket") has one of the words. */
    fun matches(text: String): Boolean {
        val tokens = Search.normalize(text).split(' ').filter { it.isNotEmpty() }
        return words.any { word -> if (' ' in word) hasPhrase(tokens, word.split(' ')) else tokens.any { has(it, word) } }
    }

    private fun has(
        token: String,
        word: String,
    ) = if (word.endsWith('*')) token.startsWith(word.dropLast(1)) else token == word

    private fun hasPhrase(
        tokens: List<String>,
        phrase: List<String>,
    ) = tokens.windowed(phrase.size).any { it == phrase }
}
