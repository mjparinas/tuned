package com.tunedplayer.app.data

import com.tunedplayer.app.core.MediaServer
import com.tunedplayer.app.core.Programme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * A Plex Media Server signed in with an [X-Plex-Token](https://www.plex.tv/claim/) (the token is the playlist's
 * password). Calls block; callers run them on I/O.
 */
class PlexServer(
    private val http: OkHttpClient,
    val server: String,
    private val token: String,
) {
    val product: MediaServer.Product = MediaServer.Product.Plex

    /** True when the server accepts the token. */
    fun signIn(): Boolean = get("/identity") != null

    fun channels(): List<MediaServer.Channel> {
        val root = get("/livetv/channels") as? JsonObject ?: return emptyList()
        val media = (root["MediaContainer"] as? JsonObject) ?: root
        return (media["Metadata"] as? JsonArray ?: media["Channel"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapIndexed { index, o ->
                val id = o.text("ratingKey") ?: o.text("key")?.substringAfterLast('/') ?: return@mapIndexed null
                MediaServer.Channel(
                    id = id,
                    name = o.text("title").orEmpty().ifBlank { "Channel" },
                    logo = thumb(o),
                    group = o.text("type")?.takeIf { it.isNotBlank() } ?: "Live TV",
                    guideId = o.text("guideKey") ?: id,
                    number = o.text("channelNum")?.toIntOrNull() ?: (index + 1),
                )
            }.filterNotNull()
    }

    fun movies(): List<MediaServer.Item> = sectionItems("movie")

    fun shows(): List<MediaServer.Item> = sectionItems("show")

    fun episodes(showId: String): List<MediaServer.Episode> {
        val root = get("/library/metadata/$showId/allLeaves") as? JsonObject ?: return emptyList()
        val media = (root["MediaContainer"] as? JsonObject) ?: root
        return (media["Metadata"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { o ->
                val id = o.text("ratingKey") ?: return@mapNotNull null
                MediaServer.Episode(
                    id = id,
                    name = o.text("title").orEmpty(),
                    season = o.int("parentIndex") ?: 1,
                    number = o.int("index") ?: 0,
                    logo = thumb(o),
                )
            }
    }

    fun guide(hours: Int): List<Programme> {
        val now = System.currentTimeMillis() / 1000
        val until = now + hours * 3600L
        val root =
            get("/livetv/epgs", mapOf("startsAt" to now.toString(), "endsAt" to until.toString())) as? JsonObject
                ?: return emptyList()
        val media = (root["MediaContainer"] as? JsonObject) ?: root
        return (media["Metadata"] as? JsonArray ?: media["Channel"] as? JsonArray)
            .orEmpty()
            .flatMap { channel ->
                val ch = channel as? JsonObject ?: return@flatMap emptyList()
                val channelId = ch.text("ratingKey") ?: ch.text("guideKey") ?: return@flatMap emptyList()
                (ch["Metadata"] as? JsonArray ?: ch["Programme"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { o ->
                        val start = o.long("beginsAt")?.times(1000) ?: return@mapNotNull null
                        val stop = o.long("endsAt")?.times(1000) ?: (start + 3_600_000L)
                        Programme(
                            channelId = channelId,
                            start = start,
                            stop = stop,
                            title = o.text("title").orEmpty().ifBlank { "Programme" },
                            description = o.text("summary"),
                            categories = listOfNotNull(o.text("genre")),
                        )
                    }
            }
    }

    /** Direct play URL for a library item. */
    fun streamFor(id: String): String {
        val key =
            partKey(id) ?: throw IOException("No playable part for $id")
        return "${base()}$key?X-Plex-Token=${enc(token)}"
    }

    private fun partKey(id: String): String? {
        val root = get("/library/metadata/$id") as? JsonObject ?: return null
        val media = (root["MediaContainer"] as? JsonObject) ?: root
        val item = (media["Metadata"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val part =
            ((item["Media"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.let { (it["Part"] as? JsonArray)?.firstOrNull() as? JsonObject }
                ?: return null
        return part.text("key")
    }

    private fun sectionItems(type: String): List<MediaServer.Item> {
        val sections = get("/library/sections") as? JsonObject ?: return emptyList()
        val media = (sections["MediaContainer"] as? JsonObject) ?: sections
        val keys =
            (media["Directory"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.text("type") == type }
                .mapNotNull { it.text("key") }
        return keys.flatMap { key ->
            val root = get("/library/sections/$key/all") as? JsonObject ?: return@flatMap emptyList()
            val container = (root["MediaContainer"] as? JsonObject) ?: root
            (container["Metadata"] as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { o ->
                    val id = o.text("ratingKey") ?: return@mapNotNull null
                    val name = o.text("title").orEmpty().ifBlank { return@mapNotNull null }
                    MediaServer.Item(
                        id = id,
                        name = name,
                        logo = thumb(o),
                        group = o.text("librarySectionTitle").orEmpty().ifBlank { type },
                        year = o.int("year"),
                        adult = false,
                    )
                }
        }
    }

    private fun thumb(o: JsonObject): String? {
        val path = o.text("thumb") ?: o.text("art") ?: return null
        return if (path.startsWith("http")) {
            path
        } else {
            "${base()}$path?X-Plex-Token=${enc(token)}"
        }
    }

    private fun get(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): JsonElement? {
        val q =
            (query + mapOf("X-Plex-Token" to token))
                .entries
                .joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val request =
            Request
                .Builder()
                .url("${base()}$path?$q")
                .header("Accept", "application/json")
                .header("X-Plex-Product", "Tuned")
                .header("X-Plex-Client-Identifier", CLIENT_ID)
                .header("X-Plex-Token", token)
                .get()
                .build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            js(response.body.string().take(MAX_LARGE))
        }
    }

    private fun base() = MediaServer.normalizeServer(server)

    private fun js(text: String): JsonElement? = try {
        JSON.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? = text(key)?.toLongOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        const val CLIENT_ID = "tuned-plex"
        const val MAX_LARGE = 8 * 1024 * 1024
    }
}
