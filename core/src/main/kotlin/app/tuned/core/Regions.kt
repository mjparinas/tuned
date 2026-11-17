package app.tuned.core

/**
 * Works out which country (or region tag) a stream belongs to, so Settings can hide whole countries.
 *
 * IPTV providers rarely fill `tvg-country`; they prefix the category instead: `US| News`, `UK: Sports`, `|CA| Kids`,
 * `[FR] Cinéma`, `EN - Movies`. The first short upper-case token of the category (or failing that, of the name) is
 * the region. Tags that only describe quality (`4K`, `HD`, `VIP`) are skipped, so `4K | US Movies` still reads US.
 */
object Regions {
    private val separators = Regex("""[|:\-\[\]()/•►▶★_]+""")
    private val quality = Regex("""^(\d+K|\d{3,4}P|[A-Z]?HD|SD|HEVC|H\.?26[45])$""", RegexOption.IGNORE_CASE)
    private val token = Regex("""^[A-Z]{2,3}$""")
    private val notRegions =
        setOf(
            "HD",
            "FHD",
            "UHD",
            "SD",
            "VIP",
            "VOD",
            "PPV",
            "TV",
            "NEW",
            "TOP",
            "ALL",
            "THE",
            "AND",
            "LIVE",
            "HEVC",
            "XXX",
            "FPS",
            "RAW",
            "MIX",
            "OFF",
            "ON",
        )
    private val aliases = mapOf("USA" to "US", "GB" to "UK", "GBR" to "UK", "CAN" to "CA", "ENG" to "EN")

    /** Display names for the tags providers use most. Unknown tags show as the tag itself. */
    private val names =
        mapOf(
            "AF" to "Afghanistan",
            "AL" to "Albania",
            "AR" to "Arabic",
            "AT" to "Austria",
            "AU" to "Australia",
            "BE" to "Belgium",
            "BG" to "Bulgaria",
            "BR" to "Brazil",
            "CA" to "Canada",
            "CH" to "Switzerland",
            "CN" to "China",
            "CZ" to "Czechia",
            "DE" to "Germany",
            "DK" to "Denmark",
            "EN" to "English",
            "ES" to "Spain",
            "EX" to "Ex-Yugoslavia",
            "FI" to "Finland",
            "FR" to "France",
            "GR" to "Greece",
            "HK" to "Hong Kong",
            "HR" to "Croatia",
            "HU" to "Hungary",
            "ID" to "Indonesia",
            "IE" to "Ireland",
            "IL" to "Israel",
            "IN" to "India",
            "IR" to "Iran",
            "IT" to "Italy",
            "JP" to "Japan",
            "KR" to "Korea",
            "KU" to "Kurdish",
            "LAT" to "Latino",
            "MX" to "Mexico",
            "MY" to "Malaysia",
            "NL" to "Netherlands",
            "NO" to "Norway",
            "NZ" to "New Zealand",
            "PH" to "Philippines",
            "PK" to "Pakistan",
            "PL" to "Poland",
            "PT" to "Portugal",
            "RO" to "Romania",
            "RS" to "Serbia",
            "RU" to "Russia",
            "SA" to "Saudi Arabia",
            "SE" to "Sweden",
            "SG" to "Singapore",
            "SK" to "Slovakia",
            "SO" to "Somalia",
            "TH" to "Thailand",
            "TR" to "Turkey",
            "TW" to "Taiwan",
            "UA" to "Ukraine",
            "UK" to "United Kingdom",
            "US" to "United States",
            "VN" to "Vietnam",
            "ZA" to "South Africa",
        )

    fun detect(
        group: String,
        tvgCountry: String?,
        name: String,
    ): String? {
        tvgCountry
            ?.split(';', ',', ' ')
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.uppercase()
            ?.let { return normalize(it) }
        return prefix(group) ?: prefix(name)
    }

    fun displayName(region: String): String = names[region] ?: region

    private fun prefix(text: String): String? {
        val parts =
            text
                .trim()
                .split(separators)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (parts.size < 2) return null // "CNN" alone is a name; a tag needs a separator after it.
        var skipped = false
        for (part in parts.take(3)) {
            if (part in notRegions || quality.matches(part)) {
                skipped = true // "4K | US Movies": look past the quality tag.
                continue
            }
            if (token.matches(part) && part !in notRegions) return normalize(part)
            val first = part.substringBefore(' ')
            if (skipped && first.length == 2 && token.matches(first) && first !in notRegions) return normalize(first)
            return null
        }
        return null
    }

    private fun normalize(tag: String): String = aliases[tag] ?: tag
}
