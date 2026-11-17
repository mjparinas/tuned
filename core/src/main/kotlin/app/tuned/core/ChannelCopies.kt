package app.tuned.core

/**
 * Backup streams (Melvin, 2026-11-17, from the app audit). Providers list the same channel several times: "ESPN",
 * "US| ESPN HD", "US| ESPN FHD", "ESPN (Backup)". When the one playing dies, another copy is usually fine. [key] reduces
 * a channel's name to what stays the same across its copies, so the player can find them and switch.
 *
 * Only the country tag in front, picture-quality words and backup markers are taken off; everything else in the name
 * stays, so "ESPN 2", "ESPN News" and "Sky Sports F1" never count as copies of "ESPN" or "Sky Sports".
 */
object ChannelCopies {
    /** Words that only describe the picture, the codec or the frame rate. */
    private val qualityWords =
        setOf(
            "sd",
            "hd",
            "fhd",
            "uhd",
            "hq",
            "lq",
            "4k",
            "8k",
            "hdr",
            "hevc",
            "h264",
            "h265",
            "x264",
            "x265",
            "264",
            "265",
            "360p",
            "480p",
            "540p",
            "576p",
            "720p",
            "1080p",
            "1080i",
            "2160p",
            "25fps",
            "30fps",
            "50fps",
            "60fps",
            "fps",
            "backup",
            "alt",
            "raw",
            "vip",
        )

    /** A marker in brackets that names a copy, not a channel: "(B)", "[Backup]", "(2)", "[720p]". */
    private val bracketMarker =
        Regex("""[\[(]\s*(?:[A-Za-z]|\d{1,2}|backup|bk|alt|raw|vip|multi|\w*\d+p|[a-z]?hd|4k|sd)\s*[\])]""", RegexOption.IGNORE_CASE)

    /** A country tag in front: "US| ", "|UK| ", "[CA] ", "EN: ", "FR - ". */
    private val countryTag = Regex("""^\s*(?:[\[(|]\s*([A-Z]{2,3})\s*[\])|]|([A-Z]{2,3})\s*[-:|])\s*""")

    /** Small capitals and superscripts some providers use for "HD" or "RAW" ("ESPN ᴴᴰ"). */
    private val decorations = Regex("""[ᴀ-ᶿʰ-˿⁰-₟★☆◉●•►▶✦✪]+""")

    /**
     * What a channel's copies have in common, or null when too little is left to tell copies apart safely (a name
     * that is only a country tag and "HD", for example).
     */
    fun key(name: String): String? {
        // Only a known country comes off, so "ABC - News" and "CBS - News" stay two channels.
        val tag = countryTag.find(name)
        val code = tag?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        val withoutTag = if (tag != null && code != null && Regions.isKnown(code)) name.substring(tag.range.last + 1) else name
        val cleaned = decorations.replace(bracketMarker.replace(withoutTag, " "), " ")
        val words = Search.normalize(cleaned).split(' ').filter { it.isNotEmpty() && it !in qualityWords }
        val key = words.joinToString(" ")
        return key.takeIf { it.length >= MIN_KEY }
    }

    /** Copies must be from the same country, or one of them has no country tag. */
    fun sameRegion(
        a: String?,
        b: String?,
    ): Boolean = a == null || b == null || a == b

    private const val MIN_KEY = 2
}
