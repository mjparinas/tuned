package app.tuned.core

/** Sorts a stream into Live TV, Movies or Shows, and spots adult categories. */
object Classifier {
    private val movieGroup = Regex("""\b(vod|movies?|films?|cinema)\b""", RegexOption.IGNORE_CASE)
    private val seriesGroup = Regex("""\b(series|shows?|tv shows?|seasons?)\b""", RegexOption.IGNORE_CASE)
    private val adultWords = Regex("""(\bxxx\b|\badults?\b|18\+|\bporn|\bfor adults\b)""", RegexOption.IGNORE_CASE)

    /**
     * Xtream Codes servers put the section in the path (`/live/`, `/movie/`, `/series/`), which is the most reliable
     * signal. Plain M3U lists don't, so the category name decides, and anything else is live.
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
            seriesGroup.containsMatchIn(group) -> ContentKind.SERIES
            movieGroup.containsMatchIn(group) -> ContentKind.MOVIE
            path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi") -> ContentKind.MOVIE
            else -> ContentKind.LIVE
        }
    }

    fun isAdult(
        group: String,
        name: String,
    ): Boolean = adultWords.containsMatchIn(group) || adultWords.containsMatchIn(name)
}
