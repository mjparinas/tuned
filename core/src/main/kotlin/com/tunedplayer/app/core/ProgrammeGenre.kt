package com.tunedplayer.app.core

/**
 * What kind of programme is on, for Live TV's "On now" filter (Melvin, 2026-11-20: "add them all"). Read from the
 * guide's own `<category>` tags first, the first one recognized winning ("Movie", "Drama" is a film); a programme
 * without one is sports only when its title is a match between two sides ("Leafs vs Canadiens"), since no other
 * kind can be told from a title alone. [code] is what the guide table stores (0 for none), so it never changes.
 */
enum class ProgrammeGenre(
    val code: Int,
    private val words: List<String>,
) {
    SPORTS(
        1,
        listOf(
            "sport*",
            "football*",
            "soccer*",
            "hockey*",
            "baseball*",
            "basketball*",
            "tennis*",
            "golf*",
            "boxing*",
            "wrestling*",
            "racing*",
            "motorsport*",
            "cricket*",
            "rugby*",
            "mma",
            "deporte*",
            "fussball*",
        ),
    ),
    MOVIES(2, listOf("movie*", "film*", "cinema*", "feature film", "pelicula*", "spielfilm*")),
    NEWS(3, listOf("news*", "current affairs", "noticias*", "nachrichten*", "actualites", "journal*")),
    KIDS(4, listOf("kid*", "child*", "children*", "cartoon*", "animation*", "animated", "family*", "infantil*", "jeunesse", "kinder*")),
    DOCUMENTARY(5, listOf("documentar*", "docu*", "doku*", "nature", "history", "wildlife")),
    ;

    /** Whether a guide category such as "Sports event" or "Children's" names this kind. */
    fun matches(category: String): Boolean {
        val tokens = Search.normalize(category).split(' ').filter { it.isNotEmpty() }
        return words.any { word ->
            if (' ' in word) {
                tokens.windowed(word.split(' ').size).any { it == word.split(' ') }
            } else if (word.endsWith('*')) {
                tokens.any { it.startsWith(word.dropLast(1)) }
            } else {
                tokens.any { it == word }
            }
        }
    }

    companion object {
        /** A match between two sides: "Leafs vs Canadiens", "Arsenal v Chelsea", "Lakers @ Celtics". */
        private val versus = Regex("""\S\s+(vs\.?|v|@)\s+\S""", RegexOption.IGNORE_CASE)

        /** The kind of a programme from its guide [categories], else from its [title]; null when it can't be told. */
        fun of(
            categories: List<String>,
            title: String,
        ): ProgrammeGenre? {
            for (category in categories) entries.firstOrNull { it.matches(category) }?.let { return it }
            return if (versus.containsMatchIn(title)) SPORTS else null
        }

        /** The kind stored as [code], or null for 0 or a code from a newer version. */
        fun fromCode(code: Int): ProgrammeGenre? = entries.firstOrNull { it.code == code }
    }
}
