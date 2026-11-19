package app.tuned.data

import app.tuned.core.PassphraseCrypto
import app.tuned.data.db.ChannelEditEntity
import app.tuned.data.db.FavouriteEntity
import app.tuned.data.db.GuideFixEntity
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
 * The copy that sync between devices shares ([CloudSyncRepository]): everything the person set up (Melvin, 2026-11-19:
 * "It should save All possible settings"). The saved playlists with their logins, every setting, the "Who's watching"
 * profiles with their avatars, PINs (as hashes) and everything each keeps, channel renames and logos, guide fixes,
 * learned intros, and the Trakt and OpenSubtitles accounts. The playlist and guide aren't in it: they download again
 * from the synced logins. (Backup files were removed, Melvin, 2026-11-18.)
 */
class SyncCopyRepository(
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val db: TunedDatabase,
    private val profiles: ProfileRepository,
    private val trakt: TraktAccount,
    private val intros: IntroStore,
) {
    /** What taking a copy changed here. */
    data class Applied(
        /** The copy signed this device in to a playlist: start its first download. */
        val signedIn: Boolean,
        /** Guide fixes changed: read the guide again so the fixed channels show theirs. */
        val guideChanged: Boolean,
    )

    @Serializable
    private data class Contents(
        val version: Int = VERSION,
        /** Every saved playlist (Settings › Playlists) except files. */
        val playlists: List<Login> = emptyList(),
        val settings: Map<String, JsonElement> = emptyMap(),
        val favourites: List<Favourite> = emptyList(),
        val watchLater: List<Later> = emptyList(),
        /** The person's own lists beside Favourites ([OwnLists]); left out while there are none, so older copies match. */
        val lists: List<OwnList> = emptyList(),
        /** Renamed channels and their own logos ([ChannelEditor]); left out while there are none. */
        val channelEdits: List<ChannelEdit> = emptyList(),
        /**
         * Every profile and what it keeps ([ProfileSync]). The [favourites], [watchLater] and [lists] above stay, for
         * the profile in use, so a device on an older version still reads the copy as it did.
         */
        val profiles: List<ProfileSync.Profile> = emptyList(),
        /** Guide entries and time offsets picked by hand; null in a copy from before they synced, which keeps this device's. */
        val guideFixes: List<GuideFix>? = null,
        /** Each show's learned intro, by show key; null in an older copy. */
        val intros: Map<String, String>? = null,
        /** The Trakt sign-in, taken when newer than a device's own ([TraktAccount.adopt]). */
        val trakt: TraktAccount.Session? = null,
        val traktScrobble: Boolean? = null,
        /** The OpenSubtitles account, for downloading subtitles. */
        val subtitles: SubtitleLogin? = null,
    )

    @Serializable
    private data class GuideFix(
        val itemId: Long,
        val guideId: String? = null,
        val offsetMinutes: Int = 0,
        val playlistTvgId: String? = null,
    )

    @Serializable
    private data class SubtitleLogin(
        val username: String,
        val password: String,
    )

    @Serializable
    private data class ChannelEdit(
        val itemId: Long,
        val name: String? = null,
        val logo: String? = null,
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
                channelEdits =
                    db
                        .channelEdits()
                        .all()
                        .map { ChannelEdit(it.itemId, it.name, it.logo) }
                        .sortedBy { it.itemId },
                profiles = ProfileSync(db).profiles(),
                guideFixes =
                    db
                        .sync()
                        .guideFixes()
                        .map { GuideFix(it.itemId, it.guideId, it.offsetMinutes, it.playlistTvgId) }
                        .sortedBy { it.itemId },
                intros = intros.all().toSortedMap(),
                trakt = trakt.session.value,
                traktScrobble = trakt.session.value?.let { trakt.scrobbleOn.value },
                subtitles = credentials.subtitleAccount()?.let { SubtitleLogin(it.username, it.password) },
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
     * Takes another device's [syncCopy]: its settings, and its profiles with their favourites, watch later and the rest
     * in place of this device's, so a removal on one device removes it here too. Its playlists are added to the saved
     * ones; a device with none in use starts on the first.
     */
    suspend fun applySyncCopy(plain: ByteArray): Applied = withContext(Dispatchers.IO) {
        val contents =
            runCatching { json.decodeFromString(Contents.serializer(), plain.decodeToString()) }
                .getOrElse { throw PassphraseCrypto.NotOurs() }
        settings.restore(contents.settings.mapNotNull { (k, v) -> decode(v)?.let { k to it } }.toMap())
        if (contents.profiles.isNotEmpty()) {
            ProfileSync(db).replace(contents.profiles)
            profiles.sync()
        } else {
            applyLegacyLibrary(contents)
        }
        ChannelEditor(db).replaceAll(contents.channelEdits.map { ChannelEditEntity(it.itemId, it.name, it.logo, "", null) })
        val guideChanged = contents.guideFixes?.let { applyGuideFixes(it) } ?: false
        contents.intros?.let { intros.replaceAll(it) }
        contents.trakt?.let { trakt.adopt(it) }
        contents.traktScrobble?.let { trakt.setScrobble(it) }
        contents.subtitles?.let { credentials.saveSubtitleAccount(SubtitleAccount(it.username, it.password)) }
        contents.playlists.forEach { credentials.remember(it.toCredentials()) }
        val first = contents.playlists.firstOrNull()
        val signedIn =
            if (credentials.load() == null && first != null) {
                credentials.save(first.toCredentials())
                true
            } else {
                false
            }
        Applied(signedIn, guideChanged)
    }

    /** Returns whether the fixes changed. */
    private suspend fun applyGuideFixes(fixes: List<GuideFix>): Boolean {
        val dao = db.sync()
        val wanted = fixes.map { GuideFixEntity(it.itemId, it.guideId, it.offsetMinutes, it.playlistTvgId) }
        val now = dao.guideFixes()
        if (now.map { it.copy(playlistTvgId = null) }.toSet() == wanted.map { it.copy(playlistTvgId = null) }.toSet()) return false
        dao.replaceGuideFixes(wanted)
        db.guideFixes().reapply()
        return true
    }

    /** A copy from before profiles synced: its favourites, watch later and lists go to the profile in use. */
    private suspend fun applyLegacyLibrary(contents: Contents) {
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
    }

    private companion object {
        /** 2 added the profiles, guide fixes, intros and accounts (2026-11-19). */
        const val VERSION = 2
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
