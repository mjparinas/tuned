package app.tuned.data

import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.HistoryEntity
import app.tuned.data.db.ListEntity
import app.tuned.data.db.ListItemEntity
import app.tuned.data.db.ProfileEntity
import app.tuned.data.db.SeenEpisodeEntity
import app.tuned.data.db.SyncDao
import app.tuned.data.db.TunedDatabase
import app.tuned.data.db.WatchLaterEntity
import kotlinx.serialization.Serializable

/**
 * The "Who's watching" profiles in the sync copy ([SyncCopyRepository]), each with what it keeps: favourites, watch
 * later, its own lists, where it left off and the episodes it has seen (Melvin, 2026-11-19: "Does this also sync user
 * accounts? It should save All possible settings"). A profile's PIN travels as its salted hash, never the PIN.
 */
class ProfileSync(
    private val db: TunedDatabase,
) {
    @Serializable
    data class Profile(
        val id: Long,
        val name: String,
        val colour: Int,
        val kids: Boolean,
        val pinHash: String? = null,
        val createdAt: Long,
        val avatar: String? = null,
        val favourites: List<Favourite> = emptyList(),
        val watchLater: List<Later> = emptyList(),
        val lists: List<OwnList> = emptyList(),
        val history: List<Progress> = emptyList(),
        val seen: List<Seen> = emptyList(),
    )

    @Serializable
    data class Favourite(
        val itemId: Long,
        val addedAt: Long,
        val position: Long,
    )

    @Serializable
    data class Later(
        val key: String,
        val itemId: Long? = null,
        val showKey: String? = null,
        val title: String,
        val image: String? = null,
        val addedAt: Long,
    )

    @Serializable
    data class OwnList(
        val id: Long,
        val name: String,
        val position: Long,
        val createdAt: Long,
        val items: List<OwnListItem> = emptyList(),
    )

    @Serializable
    data class OwnListItem(
        val itemId: Long,
        val addedAt: Long,
        val position: Long,
    )

    @Serializable
    data class Progress(
        val itemId: Long,
        val positionMs: Long,
        val durationMs: Long,
        val watchedAt: Long,
    )

    @Serializable
    data class Seen(
        val itemId: Long,
        val showKey: String,
    )

    private val dao: SyncDao get() = db.sync()

    /** Every profile and what it keeps, in a fixed order so the same state always gives the same bytes. */
    suspend fun profiles(): List<Profile> {
        val favourites = dao.favourites().groupBy { it.profileId }
        val later = dao.watchLater().groupBy { it.profileId }
        val history = dao.history().groupBy { it.profileId }
        val seen = dao.seenEpisodes().groupBy { it.profileId }
        val items = dao.listItems().groupBy { it.listId }
        val lists = dao.lists().groupBy { it.profileId }
        return dao.profiles().sortedBy { it.id }.map { p ->
            Profile(
                id = p.id,
                name = p.name,
                colour = p.colour,
                kids = p.kids,
                pinHash = p.pinHash,
                createdAt = p.createdAt,
                avatar = p.avatar,
                favourites = favourites[p.id].orEmpty().map { Favourite(it.itemId, it.addedAt, it.position) }.sortedBy { it.itemId },
                watchLater =
                    later[p.id]
                        .orEmpty()
                        .map { Later(it.key, it.itemId, it.showKey, it.title, it.image, it.addedAt) }
                        .sortedBy { it.key },
                lists =
                    lists[p.id].orEmpty().sortedBy { it.id }.map { l ->
                        OwnList(
                            l.id,
                            l.name,
                            l.position,
                            l.createdAt,
                            items[l.id].orEmpty().map { OwnListItem(it.itemId, it.addedAt, it.position) }.sortedBy { it.itemId },
                        )
                    },
                history =
                    history[p.id]
                        .orEmpty()
                        .map { Progress(it.itemId, it.positionMs, it.durationMs, it.watchedAt) }
                        .sortedBy { it.itemId },
                seen = seen[p.id].orEmpty().map { Seen(it.itemId, it.showKey) }.sortedBy { it.itemId },
            )
        }
    }

    /**
     * Puts [profiles] in place of this device's profiles and everything they kept. The profile in use here stays in use
     * when the copy has it; otherwise the first one does. An empty list (a copy from before profiles synced) changes
     * nothing.
     */
    suspend fun replace(profiles: List<Profile>) {
        if (profiles.isEmpty()) return
        dao.replaceProfiles(
            SyncDao.Kept(
                profiles = profiles.map { ProfileEntity(it.id, it.name, it.colour, it.kids, it.pinHash, it.createdAt, it.avatar) },
                favourites = profiles.flatMap { p -> p.favourites.map { FavouriteEntity(it.itemId, it.addedAt, it.position, p.id) } },
                history =
                    profiles.flatMap { p -> p.history.map { HistoryEntity(it.itemId, it.positionMs, it.durationMs, it.watchedAt, p.id) } },
                watchLater =
                    profiles.flatMap { p ->
                        p.watchLater.map { WatchLaterEntity(it.key, it.itemId, it.showKey, it.title, it.image, it.addedAt, p.id) }
                    },
                seen = profiles.flatMap { p -> p.seen.map { SeenEpisodeEntity(it.itemId, it.showKey, p.id) } },
                lists = profiles.flatMap { p -> p.lists.map { ListEntity(it.id, it.name, it.position, it.createdAt, p.id) } },
                listItems =
                    profiles.flatMap { p ->
                        p.lists.flatMap { l -> l.items.map { ListItemEntity(l.id, it.itemId, it.addedAt, it.position) } }
                    },
            ),
        )
    }
}
