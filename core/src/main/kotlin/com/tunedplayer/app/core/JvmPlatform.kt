// Named for the JVM so tv/core, which compiles :core's files by name, picks its own Platform.kt instead.
@file:Suppress("ktlint:standard:filename")

package com.tunedplayer.app.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The few things :core needs from the platform it runs on, in one place, so the rest of its rules are plain Kotlin
 * and also compile to JavaScript for the TV apps (tv/core gives the same object for JavaScript, with the same names,
 * in its own Platform.kt; this file has another name so tv/core's list of :core files can't pick it up).
 * On Android each one is the Java call :core always made, so nothing changes there.
 */
object Platform {
    /** `application/x-www-form-urlencoded`, as `URLEncoder` writes it: a space is `+`. */
    fun formEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** The reverse of [formEncode]; throws [IllegalArgumentException] for a broken `%` escape. */
    fun formDecode(value: String): String = URLDecoder.decode(value, "UTF-8")

    /** Unicode decomposition (NFD), so accents become marks of their own. */
    fun decompose(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

    /** Unicode compatibility composition (NFKC): small capitals and the like read as the plain letters. */
    fun compatible(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    /** The two-letter ISO 3166 country codes. */
    fun isoCountries(): List<String> = Locale.getISOCountries().toList()

    /** A country's name in English ("Canada"), from its ISO code. */
    fun countryName(code: String): String = Locale
        .Builder()
        .setRegion(code)
        .build()
        .getDisplayCountry(Locale.ENGLISH)

    /** The two-letter ISO 639 language codes. */
    fun isoLanguages(): List<String> = Locale.getISOLanguages().toList()

    /** A language's name in English ("French") and its three-letter code ("fra"), from its two-letter code. */
    fun languageNames(code: String): Pair<String, String> {
        val locale = Locale.Builder().setLanguage(code).build()
        return locale.getDisplayLanguage(Locale.ENGLISH) to locale.isO3Language
    }

    /** English A to Z, where case and accents don't count. */
    fun nameOrder(): Comparator<String> {
        val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
        return Comparator { a, b -> collator.compare(a, b) }
    }
}
