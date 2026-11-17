package app.tuned.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.tuned.R
import java.util.Locale

/** Languages offered for audio and captions. "" means the stream's default. Codes are what streams tag tracks with. */
val languageCodes = listOf("", "en", "fr", "es", "de", "it", "pt", "ar", "hi", "zh", "ja", "ko", "tl", "ru", "tr", "pl", "nl")

@Composable
fun languageName(code: String): String = if (code.isBlank()) {
    stringResource(R.string.language_default)
} else {
    // Read through the configuration, so the names follow a change of the device language.
    val locale = LocalConfiguration.current.locales[0]
    Locale.forLanguageTag(code).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}
