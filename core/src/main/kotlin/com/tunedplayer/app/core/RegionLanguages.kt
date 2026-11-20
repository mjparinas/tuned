package com.tunedplayer.app.core

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

    private val byRegion = languages.flatMap { (lang, regions) -> regions.map { it to lang } }.toMap()

    /** The language (ISO 639-1 code) a region tag speaks, or null when it has several or isn't known. */
    fun of(region: String): String? = byRegion[region]

    /** The tag that stands for [language] (ISO 639-1) in the language switches: "en" is EN, "es" is ES. */
    fun tagFor(language: String): String? = languages[language]?.first()

    /** The languages among [regions], each with the tags from [regions] that speak it, most tags first. */
    fun group(regions: Collection<String>): List<Pair<String, List<String>>> = regions
        .mapNotNull { r -> of(r)?.let { it to r } }
        .groupBy({ it.first }, { it.second })
        .map { (lang, tags) -> lang to tags.sorted() }
        .sortedWith(compareByDescending<Pair<String, List<String>>> { it.second.size }.thenBy { it.first })
}
