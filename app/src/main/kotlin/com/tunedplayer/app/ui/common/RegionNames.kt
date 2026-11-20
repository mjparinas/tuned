package com.tunedplayer.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.tunedplayer.app.R
import com.tunedplayer.app.core.Regions

/**
 * A country, language or area tag ("DE", "AR", "EUR") as the reader names it, in the device's language
 * (docs/decisions/languages.md): countries and languages from the JDK, areas and Tuned's own tags from strings.xml.
 */
@Composable
fun regionName(tag: String): String {
    // Read through the configuration, so the names follow a change of the device language.
    val locale = LocalConfiguration.current.locales[0]
    return areaNames[tag]?.let { stringResource(it) } ?: Regions.displayName(tag, locale)
}

private val areaNames =
    mapOf(
        "AFR" to R.string.region_africa,
        "ASIA" to R.string.region_asia,
        "EUR" to R.string.region_europe,
        "NORDIC" to R.string.region_nordic,
        "LAT" to R.string.region_latino,
        "EX" to R.string.region_ex_yugoslavia,
    )
