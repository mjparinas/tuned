package com.tunedplayer.app.core

/**
 * A channel's name for small spaces such as the home screen widget (Melvin, 2026-11-19: strip prefixes like "CA|").
 * Providers start names with a country or language tag ("CA| CP24", "|UK| Thames One", "US: Prairie Public",
 * "[DE] Rheinwelle") and end them with a picture quality ("HD", "FHD", "4K", "ᴴᴰ"), which the lists already say
 * elsewhere. Only a tag [Regions] knows comes off the front, so a real name such as "CBC - Toronto" or "ABC: Live"
 * stays whole, and a name that would be left empty is kept as it was.
 */
object ChannelNames {
    private val lead = Regex("""^\s*(?:[\[(|]\s*([A-Za-z]{2,5})\s*[\])|]\s*[-:|]?|([A-Za-z]{2,5})\s*[-:|])\s*""")
    private val trailingQuality =
        Regex(
            """(?:(?:\s+|\s*[\[(|]\s*)(?:FHD|UHD|HD|SD|4K|8K|HEVC|H\.?26[45]|\d{3,4}[Pp]|\d{2}\s?FPS)\s*[\])|]?|\s*[ᴴᵁᶠ][ᴴᴰ]+[ᴰ]?)+\s*$""",
            RegexOption.IGNORE_CASE,
        )
    private val spaces = Regex("""\s{2,}""")

    fun short(name: String): String {
        var rest = name.trim()
        // Some providers stack two tags ("|CA| EN: CP24"); take off each one Regions knows.
        repeat(2) {
            val match = lead.find(rest) ?: return@repeat
            val tag = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (!Regions.isKnown(tag)) return@repeat
            rest = rest.substring(match.range.last + 1)
        }
        rest = trailingQuality.replace(rest, "")
        val cleaned = spaces.replace(rest, " ").trim()
        return cleaned.ifEmpty { name.trim() }
    }
}
