package com.tunedplayer.app.core

import java.util.Locale

/**
 * Filtering by language (Melvin, 2026-11-17): providers tag streams by country or language (`US|`, `FR:`, `EN -`), never
 * by spoken language alone, so a language is the set of tags that speak it. Hiding French hides FR; hiding English hides
 * US, UK, CA, AU and the rest. Countries with more than one common language (Belgium, Switzerland, India) stay out, so a
 * language switch never hides more than it says; they remain under their own country.
 */
object RegionLanguages {
    private val languages =
        mapOf(
            "en" to listOf("EN", "US", "UK", "CA", "AU", "IE", "NZ"),
            "fr" to listOf("FR"),
            "es" to listOf("ES", "MX", "LAT"),
            "pt" to listOf("PT", "BR"),
            "de" to listOf("DE", "AT"),
            "it" to listOf("IT"),
            "nl" to listOf("NL"),
            "ar" to listOf("AR", "SA"),
            "tr" to listOf("TR"),
            "ru" to listOf("RU"),
            "uk" to listOf("UA"),
            "pl" to listOf("PL"),
            "el" to listOf("GR"),
            "ro" to listOf("RO"),
            "hu" to listOf("HU"),
            "cs" to listOf("CZ"),
            "sk" to listOf("SK"),
            "hr" to listOf("HR"),
            "sr" to listOf("RS"),
            "bg" to listOf("BG"),
            "sq" to listOf("AL"),
            "sv" to listOf("SE"),
            "no" to listOf("NO"),
            "da" to listOf("DK"),
            "fi" to listOf("FI"),
            "fa" to listOf("IR"),
            "he" to listOf("IL"),
            "ku" to listOf("KU"),
            "ur" to listOf("PK"),
            "zh" to listOf("CN", "TW", "HK"),
            "ja" to listOf("JP"),
            "ko" to listOf("KR"),
            "vi" to listOf("VN"),
            "th" to listOf("TH"),
            "id" to listOf("ID"),
            "ms" to listOf("MY"),
            "so" to listOf("SO"),
        )

    private val isoCountries: Set<String> = Locale.getISOCountries().toSet()

    private val byRegion = languages.flatMap { (lang, regions) -> regions.map { it to lang } }.toMap()

    /** The language (ISO 639-1 code) a region tag speaks, or null when it has several or isn't known. */
    fun of(region: String): String? = byRegion[region]

    /** The tag that stands for [language] (ISO 639-1) in the language switches: "en" is EN, "es" is ES. */
    fun tagFor(language: String): String? = languages[language]?.first()

    /**
     * Tags that name a language rather than a country ("EN - Action", "ENGLISH | Movies"), each with the countries that
     * speak it. Providers file most films and shows under such a tag, so with only Canada and the United States on, every
     * movie and show was hidden (Melvin, 2026-11-20: "When i filter by canada and united states, no movies or tv shows
     * show up"). Only EN qualifies today: the other languages' first tag is a country (ES, FR, AR) or has none beside it.
     */
    private val followers: Map<String, List<String>> =
        languages.values
            .filter { it.size > 1 && it.first() !in isoCountries }
            .associate { it.first() to it.drop(1) }

    /**
     * The language tags among [regions] that follow their countries: those with at least one of their countries there
     * too. Settings › Countries and languages leaves them out of the countries, since the language switch covers them.
     */
    fun following(regions: Collection<String>): Set<String> = followers
        .filter { (tag, countries) -> tag in regions && countries.any { it in regions } }
        .keys

    /**
     * The tags to hide, with each language tag following its countries: it shows while any of its countries the
     * playlist has ([seen]) is on, and hides when all of them are off. With none of its countries seen, its own switch
     * in [hidden] decides. So keeping only Canada and the United States shows films filed under "EN", and hiding every
     * English-speaking country hides them too.
     */
    fun effectiveHidden(
        hidden: Set<String>,
        seen: Set<String>,
    ): Set<String> {
        var result = hidden
        for ((tag, countries) in followers) {
            val known = countries.filter { it in seen }
            if (known.isEmpty()) continue
            result = if (known.all { it in hidden }) result + tag else result - tag
        }
        return result
    }

    /** The languages among [regions], each with the tags from [regions] that speak it, most tags first. */
    fun group(regions: Collection<String>): List<Pair<String, List<String>>> = regions
        .mapNotNull { r -> of(r)?.let { it to r } }
        .groupBy({ it.first }, { it.second })
        .map { (lang, tags) -> lang to tags.sorted() }
        .sortedWith(compareByDescending<Pair<String, List<String>>> { it.second.size }.thenBy { it.first })
}
