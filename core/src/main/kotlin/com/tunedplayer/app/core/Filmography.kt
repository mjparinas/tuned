package com.tunedplayer.app.core

/**
 * Everything a person has been in, oldest first (Melvin, 2026-11-17: "in chronological order all the TV shows or
 * movies that they've been in"). TMDB's combined credits list a show once per part played and include talk shows and
 * the news, where an actor only appears as themselves; those are left out so the list reads as their work.
 */
object Filmography {
    data class Credit(
        val tmdbId: Long,
        val tv: Boolean,
        val title: String,
        /** "yyyy-mm-dd", "yyyy" or null when TMDB doesn't know yet. */
        val date: String?,
        val character: String?,
        val poster: String?,
        /** Episodes they were in, for a show; null for a film or when unknown. */
        val episodes: Int?,
        val genreIds: List<Int> = emptyList(),
    ) {
        val year: String? get() = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    /** TMDB's genre ids for Talk and News: appearances as themselves, not roles. */
    private val notRoles = setOf(10767, 10763)

    /**
     * One entry per film or show, oldest first. A show with several parts keeps the earliest date, its parts joined
     * and its episodes added up. Entries without a date go last, by title.
     */
    fun order(credits: List<Credit>): List<Credit> = credits
        .asSequence()
        .filter { it.title.isNotBlank() && it.genreIds.none(notRoles::contains) }
        .groupBy { it.tv to it.tmdbId }
        .values
        .map { parts -> if (parts.size == 1) parts[0] else merge(parts) }
        .sortedWith(compareBy<Credit>({ it.date.isNullOrBlank() }, { it.date.orEmpty() }, { it.title.lowercase() }))

    private fun merge(parts: List<Credit>): Credit {
        val first = parts.minWith(compareBy({ it.date.isNullOrBlank() }, { it.date.orEmpty() }))
        val roles = parts.mapNotNull { it.character?.trim()?.ifEmpty { null } }.distinct()
        val episodes = parts.mapNotNull { it.episodes }.takeIf { it.isNotEmpty() }?.sum()
        return first.copy(
            character = roles.joinToString(" / ").ifEmpty { null },
            episodes = episodes,
            poster = first.poster ?: parts.firstNotNullOfOrNull { it.poster },
        )
    }

    /**
     * True when a playlist name such as "EN - Heat (1995) 4K" is the film or show [credit] names. The words must be
     * the same once tags and punctuation are gone, and the years must agree when both are known.
     */
    fun matches(
        playlistName: String,
        credit: Credit,
    ): Boolean {
        val query = Tmdb.query(playlistName)
        if (Search.normalize(query.title) != Search.normalize(credit.title)) return false
        val year = credit.year?.toIntOrNull()
        return query.year == null || year == null || query.year == year
    }
}
