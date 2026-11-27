package com.tunedplayer.app.core

import java.util.Locale

/**
 * [displayName] in the reader's language (the app follows the device's): the JDK names countries and the
 * language tags in [locale]'s words, so a Spanish phone reads "Alemania" and "Árabe". Areas ("Europe") and
 * tags only Tuned knows ("Latino", "Ex-Yugoslavia") keep their English name; English keeps Tuned's short names.
 */
fun Regions.displayName(
    region: String,
    locale: Locale,
): String {
    if (locale.language == Locale.ENGLISH.language) return displayName(region)
    languageTagCodes[region]?.let { code ->
        val name = Locale.forLanguageTag(code).getDisplayLanguage(locale)
        if (name.isNotBlank() && name != code) return name.replaceFirstChar { it.titlecase(locale) }
    }
    val iso = if (region == "UK") "GB" else region
    if (region !in languageTagCodes && iso in isoCodes) {
        val name =
            Locale
                .Builder()
                .setRegion(iso)
                .build()
                .getDisplayCountry(locale)
        if (name.isNotBlank() && name != iso) return name
    }
    return displayName(region)
}
