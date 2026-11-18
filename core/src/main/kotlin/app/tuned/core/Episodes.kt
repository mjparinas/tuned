package app.tuned.core

/** Reads the show, season and episode out of a series entry's title. */
object Episodes {
    // "Show S01 E02", "Show S01E02 - Title", "Show.S1.E2", "Show S01 EP02", "Show 1x02", "Show T01E02" (Spanish).
    private val sxe = Regex("""^(.*?)[\s._\-|:]*\b[ST](\d{1,4})[\s._]*EP?\.?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)

    // "Show Season 1 Episode 2", "Saison 1 Épisode 2", "Temporada 1 Episodio 2", "Staffel 1 Folge 2".
    private val words =
        Regex(
            """^(.*?)[\s._\-|:]*\b(?:season|saison|temporada|staffel|stagione|seizoen)\s*(\d{1,4})[\s._\-,:]*""" +
                """(?:episode|[ée]pisode|episodio|epis[oó]dio|folge|aflevering|ep)\.?\s*(\d{1,4})""",
            RegexOption.IGNORE_CASE,
        )
    private val nxn = Regex("""^(.*?)[\s._\-|:]*\b(\d{1,2})x(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val trailingJunk = Regex("""[\s._\-|:(\[]+$""")
    private val leadingJunk = Regex("""^[\s._\-|:)\]]+""")

    fun parse(
        title: String,
        group: String,
    ): EpisodeInfo {
        val match = sxe.find(title) ?: words.find(title) ?: nxn.find(title)
        if (match != null) {
            val show = clean(match.groupValues[1]).ifBlank { group }
            return EpisodeInfo(show, match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }
        // No numbering: the title is the show, so it still lists (as one episode) instead of vanishing.
        return EpisodeInfo(clean(title).ifBlank { group }, 1, 1)
    }

    /**
     * The episode's own name from the provider's title: the words after its number ("Severance S01E10 - The We We Are"
     * is "The We We Are"), or the whole title when it has no number. Null when the title is only the show and its
     * number ("Harbour Town S01E01"), so the list says "Episode 1" once instead of repeating the show's name.
     */
    fun title(title: String): String? {
        val match = sxe.find(title) ?: words.find(title) ?: nxn.find(title) ?: return title.trim().ifEmpty { null }
        val rest =
            title
                .substring(match.range.last + 1)
                .replace('.', ' ')
                .replace(leadingJunk, "")
                .replace(trailingJunk, "")
                .trim()
        return rest.takeIf { it.any(Char::isLetterOrDigit) }
    }

    /** A stable key for grouping episodes into one show, whatever the capitals and punctuation. */
    fun showKey(showName: String): String = Search.normalize(showName)

    private fun clean(name: String): String = name.replace('.', ' ').replace(trailingJunk, "").trim()
}
