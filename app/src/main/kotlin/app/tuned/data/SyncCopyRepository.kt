package app.tuned.data

import app.tuned.core.PassphraseCrypto
import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.ListEntity
import app.tuned.data.db.ListItemEntity
import app.tuned.data.db.TunedDatabase
import app.tuned.data.db.WatchLaterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The copy that sync between devices shares ([CloudSyncRepository]): saved playlists, settings, favourites and watch
 * later. The playlist and guide aren't in it: they download again from the synced logins. (Backup files were removed,
 * Melvin, 2026-11-18.)
 */
class SyncCopyRepository(
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val db: TunedDatabase,
) {
    @Serializable
    private data class Contents(
        val version: Int = 1,
        /** Every saved playlist (Settings › Playlists) except files. */
        val playlists: List<Login> = emptyList(),
        val settings: Map<String, JsonElement> = emptyMap(),
        val favourites: List<Favourite> = emptyList(),
        val watchLater: List<Later> = emptyList(),
        /** The person's own lists beside Favourites ([OwnLists]); left out while there are none, so older copies match. */
        val lists: List<OwnList> = emptyList(),
    )

    @Serializable
    private data class OwnList(
        val id: Long,
        val name: String,
        val position: Long,
        val createdAt: Long,
        val items: List<OwnListItem> = emptyList(),
    )

    @Serializable
    private data class OwnListItem(
        val itemId: Long,
        val addedAt: Long,
        val position: Long,
    )

    @Serializable
    private data class Later(
        val key: String,
        val itemId: Long? = null,
        val showKey: String? = null,
        val title: String,
        val image: String? = null,
        val addedAt: Long,
    )

    @Serializable
    private data class Login(
        val server: String,
        val username: String,
        val password: String,
        val playlistUrl: String = "",
        val guideUrl: String = "",
        val extraGuides: List<String> = emptyList(),
        val name: String = "",
    ) {
        fun toCredentials() = Credentials(server, username, password, playlistUrl, guideUrl, extraGuides, name)
    }

    private fun Credentials.toLogin() = Login(server, username, password, playlistUrl, guideUrl, extraGuides, name)

    @Serializable
    private data class Favourite(
        val itemId: Long,
        val addedAt: Long,
        val position: Long,
    )

    /**
     * The copy that sync between devices shares ([CloudSync]): the saved playlists, settings, favourites and watch
     * later, in a fixed order so the same state always gives the same bytes. Which playlist is in use stays each
     * device's own, so a phone and a TV can watch different ones.
     */
    suspend fun syncCopy(): ByteArray = withContext(Dispatchers.IO) {
        val contents =
            Contents(
                playlists =
                    credentials
                        .saved()
                        .filter { it.kind != Credentials.Kind.File }
                        .map { it.toLogin() }
                        .sortedWith(compareBy({ it.server }, { it.username }, { it.playlistUrl })),
                settings =
                    settings
                        .snapshot()
                        .toSortedMap()
                        .mapValues { (_, v) -> encode(v) },
                favourites =
                    db
                        .library()
                        .allFavourites()
                        .map { Favourite(it.itemId, it.addedAt, it.position) }
                        .sortedBy { it.itemId },
                watchLater =
                    db
                        .watchLater()
                        .all()
                        .first()
                        .map { Later(it.key, it.itemId, it.showKey, it.title, it.image, it.addedAt) }
                        .sortedBy { it.key },
                lists = ownLists(),
            )
        json.encodeToString(Contents.serializer(), contents).toByteArray()
    }

    private suspend fun ownLists(): List<OwnList> {
        val items = db.lists().allItems().groupBy { it.listId }
        return db
            .lists()
            .allNow()
            .sortedBy { it.id }
            .map { l ->
                OwnList(
                    l.id,
                    l.name,
                    l.position,
                    l.createdAt,
                    items[l.id].orEmpty().map { OwnListItem(it.itemId, it.addedAt, it.position) }.sortedBy { it.itemId },
                )
            }
    }

    /**
     * Takes another device's [syncCopy]: its settings, and its favourites and watch later in place of this device's,
     * so a removal on one device removes it here too. Its playlists are added to the saved ones; a device with none in
     * use starts on the first. Returns whether that signed this device in.
     */
    suspend fun applySyncCopy(plain: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val contents =
            runCatching { json.decodeFromString(Contents.serializer(), plain.decodeToString()) }
                .getOrElse { throw PassphraseCrypto.NotOurs() }
        settings.restore(contents.settings.mapNotNull { (k, v) -> decode(v)?.let { k to it } }.toMap())
        val library = db.library()
        library.clearFavourites()
        contents.favourites.forEach { library.addFavourite(FavouriteEntity(it.itemId, it.addedAt, it.position)) }
        db.lists().replaceMine(
            contents.lists.map { ListEntity(it.id, it.name, it.position, it.createdAt) },
            contents.lists.flatMap { l -> l.items.map { ListItemEntity(l.id, it.itemId, it.addedAt, it.position) } },
        )
        val later = db.watchLater()
        val keep = contents.watchLater.map { it.key }.toSet()
        later
            .all()
            .first()
            .filter { it.key !in keep }
            .forEach { later.remove(it.key) }
        contents.watchLater.forEach { later.add(WatchLaterEntity(it.key, it.itemId, it.showKey, it.title, it.image, it.addedAt)) }
        contents.playlists.forEach { credentials.remember(it.toCredentials()) }
        val first = contents.playlists.firstOrNull()
        if (credentials.load() == null && first != null) {
            credentials.save(first.toCredentials())
            true
        } else {
            false
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** A setting with its type, since JSON alone can't tell an Int from a Long or a Float. */
        fun encode(value: Any): JsonElement = buildJsonObject {
            when (value) {
                is Boolean -> put("b", value)
                is Int -> put("i", value)
                is Long -> put("l", value)
                is Float -> put("f", value)
                is String -> put("s", value)
                is Set<*> -> putJsonArray("set") { value.map { it.toString() }.sorted().forEach { add(JsonPrimitive(it)) } }
            }
        }

        fun decode(element: JsonElement): Any? {
            val (type, value) = element.jsonObject.entries.firstOrNull() ?: return null
            return runCatching {
                when (type) {
                    "b" -> value.jsonPrimitive.booleanOrNull
                    "i" -> value.jsonPrimitive.content.toInt()
                    "l" -> value.jsonPrimitive.content.toLong()
                    "f" -> value.jsonPrimitive.content.toFloat()
                    "s" -> value.jsonPrimitive.content
                    "set" -> value.jsonArray.map { it.jsonPrimitive.content }.toSet()
                    else -> null
                }
            }.getOrNull()
        }
    }
}
