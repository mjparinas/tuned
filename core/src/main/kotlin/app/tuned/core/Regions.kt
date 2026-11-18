package app.tuned.core

import java.text.Collator
import java.util.Locale

/**
 * Works out which country (or region tag) a stream belongs to, so Settings can hide whole countries.
 *
 * IPTV providers rarely fill `tvg-country`; they prefix the category instead: `US| News`, `UK: Sports`, `|CA| Kids`,
 * `[FR] Cinéma`, `EN - Movies`. The first short upper-case token of the category (or failing that, of the name) is
 * the region. Tags that only describe quality (`4K`, `HD`, `VIP`) are skipped, so `4K | US Movies` still reads US.
 */
object Regions {
    // Any run of symbols separates: "US| ", "US » ", "US ➤ ", "US ┃ ", "US ✦ " all read the same.
    private val separators = Regex("""[^\p{L}\p{N}\s+&'!]+""")
    private val spaces = Regex("""\s+""")

    /** A country tag at the end of a name: "ESPN (US)", "CNN [USA]". */
    private val bracketSuffix = Regex("""[\[(]\s*([A-Za-z]{2,3})\s*[\])]\s*$""")
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
            "BT", // BT Sport, not Bhutan
        )
    private val aliases =
        mapOf(
            "USA" to "US",
            "GB" to "UK",
            "GBR" to "UK",
            "CAN" to "CA",
            "ENG" to "EN",
            "EXYU" to "EX",
            "YU" to "EX",
            "LATAM" to "LAT",
            "UAE" to "AE",
            "KSA" to "SA",
        )

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
            "AFR" to "Africa",
        )

    /** Every tag that may name a region: the ISO country codes, plus the language and area tags providers add. */
    private val known: Set<String> =
        (Locale.getISOCountries().toSet() + names.keys + aliases.keys + "UK") - notRegions

    /**
     * Whole words providers write instead of a code: "United States | News", "ENGLISH | Movies", "Canada: News",
     * "LATINO | Deportes". Country names come from the JDK in English; language names map to the tag that speaks them.
     */
    private val wordTags: Map<String, String> =
        Locale.getISOCountries().associate {
            Locale
                .Builder()
                .setRegion(it)
                .build()
                .getDisplayCountry(Locale.ENGLISH)
                .uppercase() to
                normalize(it)
        } +
            mapOf(
                "USA" to "US",
                "AMERICA" to "US",
                "UK" to "UK",
                "GREAT BRITAIN" to "UK",
                "BRITAIN" to "UK",
                "ENGLAND" to "UK",
                "ARGENTINA" to "LAT", // AR is how providers write Arabic
                "TURKEY" to "TR",
                "KOREA" to "KR",
                "SOUTH KOREA" to "KR",
                "RUSSIA" to "RU",
                "CZECH REPUBLIC" to "CZ",
                "HOLLAND" to "NL",
                "ENGLISH" to "EN",
                "ARABIC" to "AR",
                "ARAB" to "AR",
                "LATINO" to "LAT",
                "LATIN" to "LAT",
                "LATAM" to "LAT",
                "SPANISH" to "ES",
                "FRENCH" to "FR",
                "GERMAN" to "DE",
                "ITALIAN" to "IT",
                "PORTUGUESE" to "PT",
                "BRAZILIAN" to "BR",
                "DUTCH" to "NL",
                "TURKISH" to "TR",
                "POLISH" to "PL",
                "RUSSIAN" to "RU",
                "UKRAINIAN" to "UA",
                "GREEK" to "GR",
                "ROMANIAN" to "RO",
                "HUNGARIAN" to "HU",
                "CZECH" to "CZ",
                "SLOVAK" to "SK",
                "CROATIAN" to "HR",
                "SERBIAN" to "RS",
                "BULGARIAN" to "BG",
                "ALBANIAN" to "AL",
                "SWEDISH" to "SE",
                "NORWEGIAN" to "NO",
                "DANISH" to "DK",
                "FINNISH" to "FI",
                "PERSIAN" to "IR",
                "FARSI" to "IR",
                "HEBREW" to "IL",
                "KURDISH" to "KU",
                "URDU" to "PK",
                "HINDI" to "IN",
                "INDIAN" to "IN",
                "CHINESE" to "CN",
                "JAPANESE" to "JP",
                "KOREAN" to "KR",
                "VIETNAMESE" to "VN",
                "THAI" to "TH",
                "FILIPINO" to "PH",
                "PINOY" to "PH",
                "MALAY" to "MY",
                "INDONESIAN" to "ID",
                "SOMALI" to "SO",
                "AFRICA" to "AFR",
                "AFRICAN" to "AFR",
                "EX YU" to "EX",
                "EXYU" to "EX",
            )

    /**
     * The region of one stream. `tvg-country` wins when it names a known country; then a flag or tag at the start of
     * the category or the name; then a tag in brackets at the end ("ESPN (US)"); then `tvg-language`, read as the tag
     * that speaks it, so the language switches still work for playlists that only say "English".
     */
    fun detect(
        group: String,
        tvgCountry: String?,
        name: String,
        tvgLanguage: String? = null,
    ): String? = country(tvgCountry)
        ?: flag(group)
        ?: prefix(group)
        ?: whole(group)
        ?: flag(name)
        ?: prefix(name)
        ?: suffix(name)
        ?: suffix(group)
        ?: language(tvgLanguage)

    fun displayName(region: String): String = names[region] ?: region

    /**
     * Regions in A to Z order of the name people read ("Canada" before "France"), not by how many streams each has
     * (Melvin, 2026-11-18). Accents and case don't change the order, and two tags with the same name fall back to the tag.
     */
    fun <T> sortedByName(
        items: List<T>,
        region: (T) -> String,
    ): List<T> {
        val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
        return items.sortedWith(compareBy(collator) { item: T -> displayName(region(item)) }.thenBy { region(it) })
    }

    /** A tag providers use for a country or language, such as US, UK, EN or USA. */
    fun isKnown(tag: String): Boolean = tag.uppercase() in known

    private fun first(list: String?): String? = list
        ?.split(';', ',', '|')
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }

    /** `tvg-country` holds ISO codes ("US", "gb") or names ("United States"); "INT" or "World" name nothing. */
    private fun country(value: String?): String? {
        val tag = first(value)?.let { spaces.replace(it, " ").uppercase() } ?: return null
        if (tag == "AR") return "LAT" // in tvg-country AR is Argentina, not Arabic
        return wordTags[tag] ?: tag.takeIf { it in known }?.let(::normalize)
    }

    /** `tvg-language`: "English", "eng", "en" or "Spanish;English". */
    private fun language(value: String?): String? {
        val word = first(value)?.lowercase() ?: return null
        val code = if (word.length == 2) word else languageNames[word]
        return code?.let(RegionLanguages::tagFor)
    }

    /** Language names in English and three-letter codes ("english", "eng") to the two-letter code ("en"). */
    private val languageNames: Map<String, String> by lazy {
        Locale
            .getISOLanguages()
            .flatMap { code ->
                val locale = Locale.Builder().setLanguage(code).build()
                listOf(locale.getDisplayLanguage(Locale.ENGLISH).lowercase() to code, locale.isO3Language to code)
            }.toMap()
    }

    /** A flag emoji (two regional indicator letters): "🇬🇧 Sports" reads UK. */
    private fun flag(text: String): String? {
        var i = 0
        while (i < text.length) {
            val a = text.codePointAt(i)
            val next = i + Character.charCount(a)
            if (a in REGIONAL_A..REGIONAL_Z && next < text.length) {
                val b = text.codePointAt(next)
                if (b in REGIONAL_A..REGIONAL_Z) {
                    val code = "${'A' + (a - REGIONAL_A)}${'A' + (b - REGIONAL_A)}"
                    return normalize(code).takeIf { it in known || code in known }
                }
            }
            i = next
        }
        return null
    }

    /** A category that is only a country: "UK", "USA", "United Kingdom". */
    private fun whole(group: String): String? {
        val text = spaces.replace(separators.replace(group, " "), " ").trim().uppercase()
        return wordTags[text] ?: text.takeIf { it.length in 2..3 && it in known }?.let(::normalize)
    }

    private fun suffix(text: String): String? = bracketSuffix
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.uppercase()
        ?.takeIf { it in known }
        ?.let(::normalize)

    private fun prefix(text: String): String? {
        val parts =
            text
                .trim()
                .split(separators)
                .map { spaces.replace(it, " ").trim() }
                .filter { it.isNotEmpty() }
        if (parts.size < 2) return null // "CNN" alone is a name; a tag needs a separator after it.
        var skipped = false
        for (part in parts.take(3)) {
            if (part.uppercase() in notRegions || quality.matches(part)) {
                skipped = true // "4K | US Movies": look past the quality tag.
                continue
            }
            return tag(part, skipped)
        }
        return null
    }

    /** The region one part of a category names, if any. [afterQuality] lets "US Movies" (after "4K |") read US. */
    private fun tag(
        part: String,
        afterQuality: Boolean,
    ): String? {
        if (token.matches(part) && part in known) return normalize(part)
        wordTags[part.uppercase()]?.let { return it }
        val words = part.split(' ')
        val firstWord = words.first()
        val isTag = token.matches(firstWord) && firstWord in known
        // "CA FR | Quebec": a part made only of tags is the first of them.
        val allTags = words.size > 1 && words.all { token.matches(it) && it in known }
        return if (isTag && (allTags || (afterQuality && firstWord.length == 2))) normalize(firstWord) else null
    }

    private fun normalize(tag: String): String = aliases[tag] ?: tag

    private const val REGIONAL_A = 0x1F1E6
    private const val REGIONAL_Z = 0x1F1FF
}
