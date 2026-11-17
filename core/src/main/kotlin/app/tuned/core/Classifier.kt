package app.tuned.core

/** Sorts a stream into Live TV, Movies or Shows, and spots adult categories. */
object Classifier {
    private val movieGroup = Regex("""\b(vod|movies?|films?|filmes?|cinema|pel[ií]culas?)\b""", RegexOption.IGNORE_CASE)
    private val seriesGroup =
        Regex("""\b(series|s[ée]ries|serien|shows?|tv shows?|seasons?|temporadas?|staffeln?)\b""", RegexOption.IGNORE_CASE)

    /** "Movie Channels", "24/7 Cartoons": channels that play films or shows around the clock are still live TV. */
    private val liveGroup = Regex("""\b(channels?|24\s*/\s*7|chaines|canales)\b""", RegexOption.IGNORE_CASE)

    /** An Xtream live link as `m3u_plus` writes it, with no section in the path: `http://host:port/user/pass/123.ts`. */
    private val xtreamLive = Regex("""^[a-z][a-z0-9+.-]*://[^/]+/[^/]+/[^/]+/\d+(\.(ts|m3u8))?$""")
    private val vodFile = Regex("""\.(mkv|mp4|avi|mov|m4v|webm|wmv|flv)$""")

    private val adultWords =
        Regex(
            """\bxxx\b|\badult(s|os?|es?|i)?\b|\b18\s?\+|\+\s?18\b|\bporn|\bhentai\b|\berot(ic|ik|ique|ica)\b|""" +
                """\berwachsene|\bvolwassen|\bbrazzers\b|\bhustler\b|\bplayboy\b|\bpenthouse\b|\bdorcel\b|""" +
                """\bred\s?light\b|\bbabestation\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Names that say "adult" but are for everyone. */
    private val notAdult = Regex("""\badult\s*swim\b|\byoung\s+adults?\b|\bnew\s+adult\b""", RegexOption.IGNORE_CASE)

    /**
     * Xtream Codes servers put the section in the path (`/live/`, `/movie/`, `/series/`), which is the most reliable
     * signal; `m3u_plus` writes live links with no section at all (`/user/pass/123.ts`), and those are live whatever the
     * category says, so "UK| Sky Cinema" stays in Live TV. Plain M3U lists don't, so a film file (`.mkv`, `.mp4`) is a
     * film or an episode, a category of channels is live, then the category name decides, and anything else is live.
     */
    fun kind(
        url: String,
        group: String,
    ): ContentKind {
        val path = url.substringBefore('?').lowercase()
        return when {
            "/movie/" in path -> ContentKind.MOVIE
            "/series/" in path -> ContentKind.SERIES
            "/live/" in path -> ContentKind.LIVE
            xtreamLive.matches(path) -> ContentKind.LIVE
            vodFile.containsMatchIn(path) && seriesGroup.containsMatchIn(group) -> ContentKind.SERIES
            vodFile.containsMatchIn(path) -> ContentKind.MOVIE
            liveGroup.containsMatchIn(group) -> ContentKind.LIVE
            seriesGroup.containsMatchIn(group) -> ContentKind.SERIES
            movieGroup.containsMatchIn(group) -> ContentKind.MOVIE
            else -> ContentKind.LIVE
        }
    }

    fun isAdult(
        group: String,
        name: String,
    ): Boolean = adult(group) || adult(name)

    private fun adult(text: String) = adultWords.containsMatchIn(notAdult.replace(text, " "))
}
