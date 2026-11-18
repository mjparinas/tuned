package app.tuned.data

import app.tuned.core.NewRegions
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.flow.first

/**
 * Hides a country found for the first time when most countries are hidden ([NewRegions]). Runs when the app starts,
 * to remember what's there, and after each playlist refresh, which is when new countries turn up.
 */
suspend fun settleNewRegions(
    db: TunedDatabase,
    settings: SettingsRepository,
) {
    val found = db.items().regions().first().mapTo(HashSet()) { it.region }
    settings.update { s ->
        NewRegions.settle(found, s.seenRegions, s.hiddenRegions)?.let { s.copy(hiddenRegions = it.hidden, seenRegions = it.seen) } ?: s
    }
}
