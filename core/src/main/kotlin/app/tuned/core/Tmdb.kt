package app.tuned.core

import java.net.URLEncoder

/**
 * Ratings, summaries and trailers from The Movie Database. The person brings their own key (Settings › Ratings): a
 * v3 API key goes in the link, a v4 read token in an Authorization header. Nothing here touches the network.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    const val IMAGES = "https://image.tmdb.org/t/p/w500"

    /** What to search for: the title without the provider's tags, and the year when the name has one. */
    data class Query(
        val title: String,
        val year: Int?,
    )

    data class Video(
        val site: String?,
        val type: String?,
        val key: String?,
        val official: Boolean = false,
    )

    private val languagePrefix = Regex("""^\s*(?:[\[(|]\s*[A-Za-z]{2,3}\s*[\])|]|[A-Za-z]{2,3}\s*[-:|])\s*""")
    private val yearInBrackets = Regex("""[(\[]\s*((?:19|20)\d{2})\s*[)\]]""")
    private val trailingYear = Regex("""[\s\-]((?:19|20)\d{2})\s*$""")
    private val bracketed = Regex("""[\[(][^\])]*[\])]""")
    private val qualityTags =
        Regex("""\b(?:4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|1080p|720p|2160p|MULTI|VOSTFR|SUBBED|DUBBED|HDR10?|DV)\b""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s{2,}""")

    fun query(name: String): Query {
        var text = languagePrefix.replace(name, "")
        val year =
            (yearInBrackets.find(text) ?: trailingYear.find(text))?.groupValues?.get(1)?.toIntOrNull()
        text = yearInBrackets.replace(text, " ")
        text = trailingYear.replace(text, " ")
        text = bracketed.replace(text, " ")
        text = qualityTags.replace(text, " ")
        val title = spaces.replace(text, " ").trim(' ', '-', '|', ':', '.')
        return Query(title.ifEmpty { name.trim() }, year)
    }

    /** True for a v4 read access token (a JWT), which goes in a header rather than the link. */
    fun isToken(key: String) = key.trim().startsWith("eyJ")

    fun search(
        query: Query,
        tv: Boolean,
        key: String,
    ): String {
        val year = query.year?.let { (if (tv) "&first_air_date_year=" else "&primary_release_year=") + it }.orEmpty()
        return "$API/search/${if (tv) "tv" else "movie"}?query=${enc(query.title)}$year&include_adult=false${apiKey(key)}"
    }

    fun videos(
        id: Long,
        tv: Boolean,
        key: String,
    ): String = "$API/${if (tv) "tv" else "movie"}/$id/videos?${apiKey(key).removePrefix("&")}"

    /** The YouTube trailer to offer: an official trailer first, then any trailer, then a teaser. */
    fun trailer(videos: List<Video>): String? {
        val youtube = videos.filter { it.site.equals("YouTube", ignoreCase = true) && !it.key.isNullOrBlank() }
        val pick =
            youtube.firstOrNull { it.type == "Trailer" && it.official }
                ?: youtube.firstOrNull { it.type == "Trailer" }
                ?: youtube.firstOrNull { it.type == "Teaser" }
        return pick?.key
    }

    private fun apiKey(key: String) = if (isToken(key)) "" else "&api_key=${enc(key.trim())}"

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}
