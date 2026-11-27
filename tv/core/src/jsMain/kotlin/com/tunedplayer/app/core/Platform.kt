package com.tunedplayer.app.core

/**
 * :core's `Platform` for JavaScript (core's JvmPlatform.kt is the JVM's), with the
 * same names, so the shared rules compile here unchanged. Each answers as the Java call does on Android.
 */
object Platform {
    /** As `URLEncoder.encode(value, "UTF-8")`: `encodeURIComponent`, plus `!'()~` escaped and a space as `+`. */
    fun formEncode(value: String): String {
        val encoded: String = js("encodeURIComponent")(value).unsafeCast<String>()
        return encoded
            .replace("!", "%21")
            .replace("'", "%27")
            .replace("(", "%28")
            .replace(")", "%29")
            .replace("~", "%7E")
            .replace("%20", "+")
    }

    /** As `URLDecoder.decode(value, "UTF-8")`; throws [IllegalArgumentException] for a broken `%` escape. */
    @Suppress("TooGenericExceptionCaught") // decodeURIComponent throws JavaScript's URIError, only a Throwable to Kotlin
    fun formDecode(value: String): String {
        val plain = value.replace("+", " ")
        return try {
            js("decodeURIComponent")(plain).unsafeCast<String>()
        } catch (e: Throwable) {
            throw IllegalArgumentException("Not URL encoded", e)
        }
    }

    fun decompose(text: String): String = text.asDynamic().normalize("NFD").unsafeCast<String>()

    fun compatible(text: String): String = text.asDynamic().normalize("NFKC").unsafeCast<String>()

    private val countries: Map<String, String> by lazy { table(LocaleTables.COUNTRIES).associate { it[0] to it[1] } }
    private val languages: Map<String, Pair<String, String>> by lazy {
        table(LocaleTables.LANGUAGES).associate { it[0] to (it[1] to it.getOrElse(2) { "" }) }
    }

    private fun table(text: String) = text.split('|').filter { it.isNotEmpty() }.map { it.split('=') }

    fun isoCountries(): List<String> = countries.keys.toList()

    fun countryName(code: String): String = countries[code.uppercase()] ?: code

    fun isoLanguages(): List<String> = languages.keys.toList()

    fun languageNames(code: String): Pair<String, String> = languages[code.lowercase()] ?: (code to "")

    /** English A to Z, where case and accents don't count, as the JVM's primary-strength collator. */
    fun nameOrder(): Comparator<String> {
        val options = js("({ sensitivity: 'base' })")
        return Comparator { a, b -> a.asDynamic().localeCompare(b, "en", options).unsafeCast<Int>() }
    }
}
