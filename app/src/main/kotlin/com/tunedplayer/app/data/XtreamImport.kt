package com.tunedplayer.app.data

import com.tunedplayer.app.core.PlaylistEntry
import com.tunedplayer.app.core.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Reads an Xtream server's channels, films and shows through its JSON API, for providers that turn off the M3U
 * download or leave series out of it (`core/XtreamApi`, [SyncRepository]). Each list is read one stream at a time, so a
 * list of a hundred thousand films never sits in memory.
 */
@OptIn(ExperimentalSerializationApi::class)
class XtreamImport(
    private val http: OkHttpClient,
    private val login: Credentials,
    private val userAgent: String?,
) {
    private val links = XtreamApi.Links(login.server, login.username, login.password)

    /** Live channels, then films, in the server's order. */
    suspend fun channelsAndFilms(add: suspend (PlaylistEntry) -> Unit) {
        var order = 0
        val live = categories("get_live_categories")
        each("get_live_streams") { o -> o.stream(live)?.let { add(XtreamApi.live(links, it, order++)) } }
        val vod = categories("get_vod_categories")
        each("get_vod_streams") { o -> o.stream(vod)?.let { add(XtreamApi.movie(links, it, order++)) } }
    }

    /** Every show, as the API lists them (without episodes). [first] is the sort order to start from. */
    suspend fun shows(
        first: Int,
        add: suspend (XtreamApi.Stream, Int) -> Unit,
    ) {
        var order = first
        val categories = categories("get_series_categories")
        each("get_series") { o ->
            val id = o.text("series_id") ?: return@each
            val name = o.text("name")?.takeIf { it.isNotBlank() } ?: return@each
            add(XtreamApi.Stream(id, name, o.text("cover"), categories[o.text("category_id")].orEmpty()), order++)
        }
    }

    /** One show's episodes (`get_series_info`), filed under [show] so they join its stand-in row. */
    suspend fun episodes(
        seriesId: String,
        show: String,
        category: String,
        cover: String?,
        firstOrder: Int,
    ): List<PlaylistEntry> = withContext(Dispatchers.IO) {
        val body =
            http.newCall(request(XtreamApi.seriesInfo(login.server, login.username, login.password, seriesId))).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                r.body.string()
            }
        val seasons = (Json.parseToJsonElement(body) as? JsonObject)?.get("episodes")
        // Servers send the seasons as an object ({"1": [...]}) or, when there is only one, as a bare array.
        val lists =
            when (seasons) {
                is JsonObject -> seasons.values
                is JsonArray -> listOf(seasons)
                else -> emptyList()
            }
        var order = firstOrder
        lists
            .flatMap { (it as? JsonArray).orEmpty() }
            .mapNotNull { element ->
                val o = element as? JsonObject ?: return@mapNotNull null
                val id = o.text("id") ?: return@mapNotNull null
                val season = o.text("season")?.toIntOrNull() ?: 1
                val number = o.text("episode_num")?.toIntOrNull() ?: 1
                val still = (o["info"] as? JsonObject)?.text("movie_image")
                XtreamApi.episode(
                    links,
                    show,
                    category,
                    still ?: cover,
                    id,
                    o.text("title"),
                    season,
                    number,
                    o.text("container_extension"),
                    order++,
                )
            }.sortedWith(compareBy({ it.episode?.season }, { it.episode?.episode }))
    }

    private suspend fun categories(action: String): Map<String, String> = withContext(Dispatchers.IO) {
        http.newCall(request(action(action))).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            (Json.parseToJsonElement(r.body.string()) as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { o -> o.text("category_id")?.let { id -> id to o.text("category_name").orEmpty() } }
                .toMap()
        }
    }

    private suspend fun each(
        action: String,
        read: suspend (JsonObject) -> Unit,
    ) = withContext(Dispatchers.IO) {
        http.newCall(request(action(action))).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            for (o in Json.decodeToSequence(r.body.byteStream(), JsonObject.serializer())) read(o)
        }
    }

    private fun JsonObject.stream(categories: Map<String, String>): XtreamApi.Stream? {
        val id = text("stream_id") ?: return null
        val name = text("name")?.takeIf { it.isNotBlank() } ?: return null
        return XtreamApi.Stream(
            id = id,
            name = name,
            icon = text("stream_icon"),
            category = categories[text("category_id")].orEmpty(),
            epgId = text("epg_channel_id"),
            adult = text("is_adult") == "1",
            extension = text("container_extension"),
        )
    }

    private fun action(action: String) = XtreamApi.action(login.server, login.username, login.password, action)

    private fun request(url: String) = Request
        .Builder()
        .url(url)
        .apply { userAgent?.let { header("User-Agent", it) } }
        .build()

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
