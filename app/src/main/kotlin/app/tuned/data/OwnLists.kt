package app.tuned.data

import app.tuned.core.ContentKind
import app.tuned.data.db.ListEntity
import app.tuned.data.db.ListItemEntity
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

/**
 * The person's own lists of channels and films beside Favourites, like playlists in a music app (Melvin, 2026-11-19).
 * The heart still adds to Favourites in one tap; "Add to list" in a channel's or film's menu adds to these. Premium in
 * the store build (docs/decisions/premium.md); each profile keeps its own, and sync carries them.
 */
class OwnLists(
    private val db: TunedDatabase,
) {
    val all: Flow<List<ListEntity>> = db.lists().all()

    /** The lists with something of [kind] in them, for that tab's chips. */
    fun withKind(kind: ContentKind): Flow<List<ListEntity>> = db.lists().withKind(kind.code)

    /** Which lists hold [itemId]. */
    fun holding(itemId: Long): Flow<List<Long>> = db.lists().listsWith(itemId)

    /** Makes a list named [name] (after the person's others) and puts [firstItem] in it; returns its id. */
    suspend fun create(
        name: String,
        firstItem: Long?,
    ): Long {
        val now = System.currentTimeMillis()
        val id = Random.nextLong(1, Long.MAX_VALUE)
        val position = (db.lists().allNow().maxOfOrNull { it.position } ?: -1) + 1
        db.lists().create(ListEntity(id, name.trim(), position, now))
        if (firstItem != null) set(id, firstItem, true)
        return id
    }

    suspend fun set(
        listId: Long,
        itemId: Long,
        on: Boolean,
    ) {
        if (on) db.lists().addItem(ListItemEntity(listId, itemId, System.currentTimeMillis())) else db.lists().removeItem(listId, itemId)
    }

    suspend fun rename(
        listId: Long,
        name: String,
    ) = db.lists().rename(listId, name.trim())

    suspend fun delete(listId: Long) = db.lists().delete(listId)
}
