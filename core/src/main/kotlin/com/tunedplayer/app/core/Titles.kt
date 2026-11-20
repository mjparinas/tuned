package com.tunedplayer.app.core

/**
 * The name to show for a film or show. Providers often start names with a language tag ("EN - Top Gun",
 * "|EN| Top Gun", "[FR] Amélie"), which the language filter already covers and which only clutters a title.
 * Only capital tags are taken off, so a real title such as "Up - The Story" or "Her: Part 1" stays as it is. Two
 * more styles some providers use are taken off too: "Ex - " for ex-Yugoslav titles (written in any case, since "Ex"
 * starts no title with a dash after it), and a known country tag followed by two or more spaces ("ES  Mixed by Erry").
 */
object Titles {
    private val prefix = Regex("""^\s*(?:[\[(|]\s*[A-Z]{2,3}\s*[\])|]|[A-Z]{2,3}\s*[-:|])\s*""")
    private val spaces = Regex("""\s{2,}""")
    private val exYu = Regex("""^\s*(?:EXYU|EX)\s*[-:|]\s*""", RegexOption.IGNORE_CASE)
    private val spacedTag = Regex("""^\s*([A-Z]{2,3})\s{2,}""")

    fun display(name: String): String {
        val tagless =
            spacedTag.find(name)?.takeIf { Regions.isKnown(it.groupValues[1]) }?.let { name.substring(it.range.last + 1) }
                ?: name
        val cleaned = spaces.replace(prefix.replace(exYu.replace(tagless, ""), ""), " ").trim()
        return cleaned.ifEmpty { name.trim() }
    }
}
