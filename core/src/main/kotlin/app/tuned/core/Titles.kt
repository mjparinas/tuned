package app.tuned.core

/**
 * The name to show for a film or show. Providers often start names with a language tag ("EN - Top Gun",
 * "|EN| Top Gun", "[FR] Amélie"), which the language filter already covers and which only clutters a title.
 * Only capital tags are taken off, so a real title such as "Up - The Story" or "Her: Part 1" stays as it is.
 */
object Titles {
    private val prefix = Regex("""^\s*(?:[\[(|]\s*[A-Z]{2,3}\s*[\])|]|[A-Z]{2,3}\s*[-:|])\s*""")
    private val spaces = Regex("""\s{2,}""")

    fun display(name: String): String {
        val cleaned = spaces.replace(prefix.replace(name, ""), " ").trim()
        return cleaned.ifEmpty { name.trim() }
    }
}
