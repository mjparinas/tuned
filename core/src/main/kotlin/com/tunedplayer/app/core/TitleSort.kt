package com.tunedplayer.app.core

/**
 * What Movies and Shows can be sorted by (Melvin, 2026-11-20: "sort by newest first or sort by the rating"), and the
 * fields behind each sort, read from what the provider sends. The provider's own order stays the default.
 */
object TitleSort {
    /** The sorts. Settings stores them by name, so the names never change once released. */
    enum class Order {
        /** The provider's order: the playlist as it came. */
        PROVIDER,

        /** Newest additions first: the provider's "added" time, or when Tuned first saw the title. */
        ADDED,

        /** Newest release year first; titles without a year go last. */
        YEAR,

        /** Highest rated first; titles without a rating go last. */
        RATING,

        /** A to Z by title, without the provider's language and quality tags ("EN - ", "4K"). */
        TITLE,
    }

    private val digits = Regex("""^\d+$""")
    private val yearAtStart = Regex("""^\s*((?:19|20)\d{2})""")

    /** The Xtream API's `added` or `last_modified` (epoch seconds, as text); 0 when missing or not a time. */
    fun added(text: String?): Long {
        val t = text?.trim().orEmpty()
        if (!digits.matches(t) || t.length > MAX_DIGITS) return 0
        return t.toLong().takeIf { it in MIN_TIME..MAX_TIME } ?: 0
    }

    /**
     * The provider's rating out of 10 (`rating`), or its five-star one doubled (`rating_5based`). Providers send 0 or
     * nothing for "no rating", so 0 counts as none.
     */
    fun rating(
        tenBased: String?,
        fiveBased: String? = null,
    ): Double? {
        val ten =
            tenBased
                ?.trim()
                ?.replace(',', '.')
                ?.toDoubleOrNull()
                ?.takeIf { it > 0 && it <= TEN }
        if (ten != null) return ten
        return fiveBased
            ?.trim()
            ?.replace(',', '.')
            ?.toDoubleOrNull()
            ?.takeIf { it > 0 && it <= FIVE }
            ?.let { it * 2 }
    }

    /** A release year from the provider's `year`, or the start of its `releaseDate` ("2021-05-04"); null otherwise. */
    fun year(vararg fields: String?): Int? = fields.firstNotNullOfOrNull { field ->
        yearAtStart
            .find(field.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    /** The year in a title's name ("Dune (2021)", "Dune 2021"), as TMDB lookups read it. */
    fun yearInName(name: String): Int? = Tmdb.query(name).year

    /** What A to Z sorts on: the title without tags or year, lower case and without accents ("EN - Élan (2020)" → "elan"). */
    fun title(name: String): String = Search.normalize(Tmdb.query(name).title).ifEmpty { name.trim().lowercase() }

    private const val MAX_DIGITS = 12
    private const val TEN = 10.0
    private const val FIVE = 5.0

    /** 1990-01-01 to 2100-01-01: anything outside is not a real "added" time. */
    private const val MIN_TIME = 631_152_000L
    private const val MAX_TIME = 4_102_444_800L
}
