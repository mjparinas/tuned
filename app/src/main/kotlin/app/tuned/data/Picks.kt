package app.tuned.data

import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.TunedDatabase
import app.tuned.data.db.WatchLaterEntity
import app.tuned.diagnostics.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A set the person changes with a tap, such as favourites, that shows the change at once and saves it behind the scenes
 * (Melvin, 2026-11-17: tapping a heart took seconds). Until the database has the change, it is kept here and laid over
 * what the database says; once the database agrees, it is dropped. Saves run one at a time in tap order, so a quick
 * on-then-off ends off. A save that fails is dropped too, so the screen goes back to the truth.
 */
class OptimisticSet<K>(
    saved: Flow<Collection<K>>,
    private val scope: CoroutineScope,
) {
    private val pending = MutableStateFlow<Map<K, Boolean>>(emptyMap())
    private val writes = Mutex()

    val value: StateFlow<Set<K>> =
        combine(
            saved
                .map { it.toHashSet() }
                .onEach { stored -> pending.update { p -> p.filterNot { (key, on) -> (key in stored) == on } } },
            pending,
        ) { stored, p ->
            if (p.isEmpty()) {
                stored
            } else {
                HashSet(stored).apply { p.forEach { (key, on) -> if (on) add(key) else remove(key) } }
            }
        }.stateIn(scope, SharingStarted.Lazily, emptySet())

    fun set(
        key: K,
        on: Boolean,
        write: suspend () -> Unit,
    ) {
        pending.update { it + (key to on) }
        scope.launch {
            writes.withLock {
                attempt("Picks", "Couldn't save the change") { write() }
                    .onFailure { pending.update { p -> if (p[key] == on) p - key else p } }
            }
        }
    }
}

/** Favourites and Watch later, shared by every screen so a tap shows everywhere at once. */
class Picks(
    private val db: TunedDatabase,
    scope: CoroutineScope,
    /** After a favourite is added or taken away (the TV home screen's row follows). */
    private val onFavouritesChanged: () -> Unit = {},
) {
    val favourites = OptimisticSet(db.library().favouriteIds(), scope)
    val watchLater = OptimisticSet(db.watchLater().keys(), scope)

    fun setFavourite(
        itemId: Long,
        on: Boolean,
    ) = favourites.set(itemId, on) {
        if (on) db.library().addFavourite(FavouriteEntity(itemId, System.currentTimeMillis())) else db.library().removeFavourite(itemId)
        onFavouritesChanged()
    }

    fun setWatchLater(
        row: WatchLaterEntity,
        on: Boolean,
    ) = watchLater.set(row.key, on) { if (on) db.watchLater().add(row) else db.watchLater().remove(row.key) }
}
