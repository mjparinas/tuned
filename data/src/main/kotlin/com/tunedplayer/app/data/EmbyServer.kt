package com.tunedplayer.app.data

import com.tunedplayer.app.core.MediaServer
import com.tunedplayer.app.core.Programme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * A Jellyfin or Emby server signed in with a username and password. Both share the MediaBrowser API
 * (`AuthenticateByName`, `/Users/{id}/Items`, Live TV). Calls block; callers run them on I/O.
 */
class EmbyServer(
    private val http: OkHttpClient,
    val server: String,
    private val username: String,
    private val password: String,
    val product: MediaServer.Product,
) {
    @Volatile private var token: String? = null

    @Volatile private var userId: String? = null

    /** Signs in. False when the server turns the login away. */
    fun signIn(): Boolean {
        synchronized(this) {
            token = null
            userId = null
            val body =
                """{"Username":"${esc(username)}","Pw":"${esc(password)}"}""".toRequestBody(JSON)
            val request =
                Request
                    .Builder()
                    .url("${base()}/Users/AuthenticateByName")
                    .header("Authorization", authHeader(token = null))
                    .header("X-Emby-Authorization", authHeader(token = null))
                    .post(body)
                    .build()
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) return false
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val root = js(response.body.string().take(MAX_SMALL)) as? JsonObject ?: return false
                val access = root.text("AccessToken") ?: return false
                val user = (root["User"] as? JsonObject)?.text("Id") ?: return false
                token = access
                userId = user
                return true
            }
        }
    }

    fun ensureSignedIn() {
        if (token != null && userId != null) return
        if (!signIn()) throw IOException("Login refused")
    }

    /** Live TV channels, when the server has any. */
    fun channels(): List<MediaServer.Channel> {
        ensureSignedIn()
        val uid = userId ?: return emptyList()
        val root =
            get("/LiveTv/Channels", mapOf("UserId" to uid, "EnableFavoriteSorting" to "false")) as? JsonObject
                ?: return emptyList()
        return (root["Items"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapIndexed { index, o ->
                val id = o.text("Id") ?: return@mapIndexed null
                val name = o.text("Name").orEmpty().ifBlank { "Channel" }
                MediaServer.Channel(
                    id = id,
                    name = name,
                    logo = imageUrl(id),
                    group = o.text("ChannelType")?.takeIf { it.isNotBlank() } ?: "Live TV",
                    guideId = o.text("Id"),
                    number = o.int("ChannelNumber") ?: (index + 1),
                )
            }.filterNotNull()
    }

    /** Films in every library folder. */
    fun movies(): List<MediaServer.Item> = libraryItems("Movie")

    /** Shows as stand-ins (episodes load when a show is opened). */
    fun shows(): List<MediaServer.Item> = libraryItems("Series")

    fun episodes(showId: String): List<MediaServer.Episode> {
        ensureSignedIn()
        val uid = userId ?: return emptyList()
        val root =
            get(
                "/Shows/$showId/Episodes",
                mapOf(
                    "UserId" to uid,
                    "Fields" to "Overview,PrimaryImageAspectRatio",
                ),
            ) as? JsonObject ?: return emptyList()
        return (root["Items"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val id = o.text("Id") ?: return@mapNotNull null
                MediaServer.Episode(
                    id = id,
                    name = o.text("Name").orEmpty(),
                    season = o.int("ParentIndexNumber") ?: 1,
                    number = o.int("IndexNumber") ?: 0,
                    logo = imageUrl(id),
                )
            }
    }

    /** Guide programmes for the next [hours], filed under each channel's id. */
    fun guide(hours: Int): List<Programme> {
        ensureSignedIn()
        val uid = userId ?: return emptyList()
        val now = System.currentTimeMillis()
        val until = now + hours * 3_600_000L
        val root =
            get(
                "/LiveTv/Programs",
                mapOf(
                    "UserId" to uid,
                    "MinEndDate" to iso(now),
                    "MaxStartDate" to iso(until),
                    "EnableImageTypes" to "Primary",
                    "Fields" to "ChannelInfo,Overview",
                ),
            ) as? JsonObject ?: return emptyList()
        return (root["Items"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val channelId = o.text("ChannelId") ?: return@mapNotNull null
                val start = o.time("StartDate") ?: return@mapNotNull null
                val stop = o.time("EndDate") ?: (start + 3_600_000L)
                Programme(
                    channelId = channelId,
                    start = start,
                    stop = stop,
                    title = o.text("Name").orEmpty().ifBlank { "Programme" },
                    description = o.text("Overview"),
                    categories = (o["Genres"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                )
            }
    }

    /** Direct stream URL for an item id (static file when the server allows it). */
    fun streamFor(id: String): String {
        ensureSignedIn()
        val access = token ?: throw IOException("Not signed in")
        return "${base()}/Videos/$id/stream?static=true&api_key=${enc(access)}"
    }

    private fun libraryItems(type: String): List<MediaServer.Item> {
        ensureSignedIn()
        val uid = userId ?: return emptyList()
        val root =
            get(
                "/Users/$uid/Items",
                mapOf(
                    "IncludeItemTypes" to type,
                    "Recursive" to "true",
                    "SortBy" to "SortName",
                    "SortOrder" to "Ascending",
                    "Fields" to "Genres,ProductionYear,PrimaryImageAspectRatio,Path",
                ),
            ) as? JsonObject ?: return emptyList()
        return (root["Items"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val id = o.text("Id") ?: return@mapNotNull null
                val name = o.text("Name").orEmpty().ifBlank { return@mapNotNull null }
                val genres = (o["Genres"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                MediaServer.Item(
                    id = id,
                    name = name,
                    logo = imageUrl(id),
                    group = genres.firstOrNull().orEmpty().ifBlank { type },
                    year = o.int("ProductionYear"),
                    adult = genres.any { it.contains("adult", ignoreCase = true) },
                )
            }
    }

    private fun imageUrl(id: String): String? {
        val access = token ?: return null
        return "${base()}/Items/$id/Images/Primary?maxHeight=400&quality=90&api_key=${enc(access)}"
    }

    private fun get(
        path: String,
        query: Map<String, String>,
    ): JsonElement? {
        val access = token
        val url =
            base() +
                path +
                query.entries.joinToString("&", prefix = "?") { "${enc(it.key)}=${enc(it.value)}" }
        val request =
            Request
                .Builder()
                .url(url)
                .header("Authorization", authHeader(access))
                .header("X-Emby-Authorization", authHeader(access))
                .apply { access?.let { header("X-Emby-Token", it) } }
                .get()
                .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401) {
                // Token expired: sign in again once.
                if (!signIn()) throw IOException("Login refused")
                return get(path, query)
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            js(response.body.string().take(MAX_LARGE))
        }
    }

    private fun authHeader(token: String?): String {
        val parts =
            buildList {
                add("""MediaBrowser Client="Tuned"""")
                add("""Device="Android"""")
                add("""DeviceId="$DEVICE_ID"""")
                add("""Version="1"""")
                if (token != null) add("""Token="$token"""")
            }
        return parts.joinToString(", ")
    }

    private fun base() = MediaServer.normalizeServer(server)

    private fun js(text: String): JsonElement? = try {
        JSON_PARSE.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? =
        text(key)?.toIntOrNull() ?: (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

    private fun JsonObject.time(key: String): Long? {
        val raw = text(key) ?: return null
        return try {
            java.time.Instant
                .parse(raw)
                .toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun iso(ms: Long): String = java.time.Instant
        .ofEpochMilli(ms)
        .toString()

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun esc(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val JSON_PARSE = Json { ignoreUnknownKeys = true }
        val DEVICE_ID = UUID.nameUUIDFromBytes("tuned-emby".toByteArray()).toString()
        const val MAX_SMALL = 64 * 1024
        const val MAX_LARGE = 8 * 1024 * 1024
    }
}
