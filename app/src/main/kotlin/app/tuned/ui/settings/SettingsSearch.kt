package app.tuned.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.staticCompositionLocalOf
import app.tuned.core.Search

/**
 * Search in Settings (Melvin, 2026-11-18: "build all three", from the UI review). The results are the pages' own rows,
 * drawn under a query: each row widget asks [searchHides] whether it matches and draws nothing if not, so a new
 * setting is found with no list to keep up to date, and a switch can be flipped right in the results.
 */
internal class SettingsQuery(
    /** The typed words, normalized as search does everywhere (case, accents and punctuation ignored). */
    val words: List<String>,
    /** How many rows of this page match, so the page's name shows only above a page with something to show. */
    val matches: MutableIntState,
)

internal val LocalSettingsQuery = staticCompositionLocalOf<SettingsQuery?> { null }

/** True while searching Settings, when none of [texts] (a row's title and what it says now) has every typed word. */
@Composable
internal fun searchHides(vararg texts: String?): Boolean {
    val query = LocalSettingsQuery.current ?: return false
    val haystack = Search.normalize(texts.filterNotNull().joinToString(" "))
    val found = query.words.all { it in haystack }
    if (found) {
        DisposableEffect(query) {
            query.matches.intValue++
            onDispose { query.matches.intValue-- }
        }
    }
    return !found
}

/** True while a search is on, when headers and anything that isn't a setting step aside. */
@Composable
internal fun searching(): Boolean = LocalSettingsQuery.current != null
