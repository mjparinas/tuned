package app.tuned.core

import java.text.Normalizer

/** Search text: lower case, no accents, single spaces, so "Télé-Québec" matches "tele quebec". */
object Search {
    private val marks = Regex("""\p{Mn}+""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    fun normalize(text: String): String = Normalizer
        .normalize(text, Normalizer.Form.NFD)
        .replace(marks, "")
        .lowercase()
        .replace(nonWord, " ")
        .trim()
}
