package app.tuned.data

import app.tuned.core.BackupCrypto
import app.tuned.data.db.FavouriteEntity
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
 * Backup and restore (Melvin, 2026-11-17): the login, every setting and the favourites in one file sealed with a
 * passphrase ([BackupCrypto]), so moving to a new phone or reinstalling is one step. The playlist and guide aren't in
 * it: they download again from the restored login.
 */
class BackupRepository(
    private val credentials: CredentialStore,
    private val settings: SettingsRepository,
    private val db: TunedDatabase,
) {
    @Serializable
    private data class Contents(
        val version: Int = 1,
        val login: Login? = null,
        /** Every saved playlist (Settings › Playlists), so a restore brings back the whole list. */
        val playlists: List<Login> = emptyList(),
        val settings: Map<String, JsonElement> = emptyMap(),
        val favourites: List<Favourite> = emptyList(),
        val watchLater: List<Later> = emptyList(),
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

    /** What a restore brought back, so the caller knows whether to start downloading. */
    data class Restored(
        val signedIn: Boolean,
        val favourites: Int,
    )

    suspend fun export(passphrase: CharArray): ByteArray = withContext(Dispatchers.IO) {
        val contents =
            Contents(
                login = credentials.load()?.toLogin(),
                // A playlist picked from a file lives on this device only, so it isn't in the backup.
                playlists = credentials.saved().filter { it.kind != Credentials.Kind.File }.map { it.toLogin() },
                settings = settings.snapshot().mapValues { (_, v) -> encode(v) },
                favourites = db.library().allFavourites().map { Favourite(it.itemId, it.addedAt, it.position) },
                watchLater =
                    db
                        .watchLater()
                        .all()
                        .first()
                        .map { Later(it.key, it.itemId, it.showKey, it.title, it.image, it.addedAt) },
            )
        BackupCrypto.seal(json.encodeToString(Contents.serializer(), contents).toByteArray(), passphrase)
    }

    /** Throws [BackupCrypto.WrongPassphrase] or [BackupCrypto.NotABackup]; nothing changes unless the whole file reads. */
    suspend fun import(
        sealed: ByteArray,
        passphrase: CharArray,
    ): Restored = withContext(Dispatchers.IO) {
        val plain = BackupCrypto.open(sealed, passphrase)
        val contents =
            runCatching { json.decodeFromString(Contents.serializer(), plain.decodeToString()) }
                .getOrElse { throw BackupCrypto.NotABackup() }
        settings.restore(contents.settings.mapNotNull { (k, v) -> decode(v)?.let { k to it } }.toMap())
        contents.favourites.forEach { db.library().addFavourite(FavouriteEntity(it.itemId, it.addedAt, it.position)) }
        contents.watchLater.forEach { db.watchLater().add(WatchLaterEntity(it.key, it.itemId, it.showKey, it.title, it.image, it.addedAt)) }
        contents.playlists.forEach { credentials.remember(it.toCredentials()) }
        contents.login?.let {
            val login = it.toCredentials()
            credentials.save(login)
            credentials.remember(login)
        }
        Restored(signedIn = contents.login != null, favourites = contents.favourites.size)
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
                is Set<*> -> putJsonArray("set") { value.forEach { add(JsonPrimitive(it.toString())) } }
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
