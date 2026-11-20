package com.tunedplayer.app.core

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

    /**
     * The full-text query for [text]: every word, each also matching longer words that start with it, all required.
     * "top gu" becomes `top* gu*`. Normalized text has only letters, digits and single spaces, so nothing needs
     * escaping, and FTS operators (which are upper case) can't appear.
     */
    fun match(text: String): String = normalize(text).split(' ').filter { it.isNotEmpty() }.joinToString(" ") { "$it*" }
}
