package app.tuned.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import app.tuned.core.Programme
import app.tuned.core.Stalker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException

/**
 * One Stalker portal signed in to with a MAC address (`core/Stalker`). Calls block, so callers run them on an I/O
 * thread; the player's data source already is one. The portal's token is kept for the session and fetched again when
 * the portal stops taking it. The big lists (every channel, the guide) are read one value at a time, so a portal with
 * thousands of channels never sits in memory as one tree.
 */
class StalkerPortal(
    private val http: OkHttpClient,
    val portal: String,
    val mac: String,
) {
    @Volatile private var api: String? = null

    @Volatile private var token: String? = null

    /** Shakes hands and sends the box's profile. False when the portal turns the MAC away. */
    fun signIn(): Boolean {
        synchronized(this) {
            token = null
            for (candidate in apiCandidates()) {
                val found =
                    try {
                        handshake(candidate)
                    } catch (_: IOException) {
                        null
                    }
                if (found != null) {
                    api = candidate
                    token = found
                    break
                }
            }
        }
        val api = api ?: return false
        val token = token ?: return false
        // Some portals only turn the token on once the box has sent its profile; a portal that won't answer it still plays.
        val url = url(api, mapOf("type" to "stb", "action" to "get_profile") + profileParams())
        val profile =
            http.newCall(request(url, token)).execute().use { r ->
                if (r.isSuccessful) js(r.body.string().take(MAX_SMALL_ANSWER)) else null
            }
        return (profile as? JsonObject)?.text("blocked") != "1"
    }

    /** Every live channel, with its genre's name, in the portal's order. */
    fun channels(): List<Stalker.Channel> {
        val genres =
            (call(mapOf("type" to "itv", "action" to "get_genres")) { js(it) } as? JsonArray)
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { o -> o.text("id")?.let { id -> id to o.text("title").orEmpty() } }
                .toMap()
        return streamed(mapOf("type" to "itv", "action" to "get_all_channels")) { reader -> readChannels(reader, genres) }
    }

    /** The stream to open for a channel's command, fresh from the portal. */
    fun streamFor(link: Stalker.Link): String {
        val answer =
            try {
                call(
                    mapOf(
                        "type" to link.type,
                        "action" to "create_link",
                        "cmd" to link.cmd,
                        "series" to "",
                        "forced_storage" to "undefined",
                        "disable_ad" to "0",
                        "download" to "0",
                    ),
                ) { js(it) }
            } catch (e: IOException) {
                // A command that already names its stream still plays when the portal doesn't answer.
                return Stalker.direct(link.cmd) ?: throw e
            }
        val cmd = (answer as? JsonObject)?.text("cmd") ?: (answer as? JsonPrimitive)?.contentOrNull
        return cmd?.let(Stalker::address) ?: Stalker.direct(link.cmd) ?: throw IOException("The portal gave no stream for this channel")
    }

    /** The guide for the next [hours], each programme filed under the channel's portal id. */
    fun guide(
        hours: Int,
        each: (Programme) -> Unit,
    ) {
        streamed(mapOf("type" to "itv", "action" to "get_epg_info", "period" to hours.toString())) { reader -> readGuide(reader, each) }
    }

    private fun apiCandidates(): List<String> = listOfNotNull(api) + Stalker.apiCandidates(portal).filter { it != api }

    private fun handshake(candidate: String): String? {
        val url = url(candidate, mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"))
        return http.newCall(request(url, null)).execute().use { r ->
            if (!r.isSuccessful) return@use null
            (js(r.body.string().take(MAX_SMALL_ANSWER)) as? JsonObject)?.text("token")?.takeIf { it.isNotBlank() }
        }
    }

    private fun profileParams(): Map<String, String> = mapOf(
        "hd" to "1",
        "ver" to "ImageDescription: 0.2.18-r23-250; PORTAL version: 5.6.1; API Version: JS API version: 343",
        "num_banks" to "2",
        "sn" to Stalker.serial(mac),
        "stb_type" to "MAG250",
        "image_version" to "218",
        "video_out" to "hdmi",
        "device_id" to Stalker.deviceId(mac),
        "device_id2" to Stalker.deviceId(mac),
        "signature" to Stalker.signature(mac),
        "auth_second_step" to "1",
        "hw_version" to "1.7-BD-00",
        "not_valid_token" to "0",
        "api_signature" to "262",
        "prehash" to "",
    )

    /** A small call: its answer read whole. A refused token signs in again once and asks again. */
    private fun <T> call(
        params: Map<String, String>,
        read: (String) -> T?,
    ): T = withSession { api, token ->
        http.newCall(request(url(api, params), token)).execute().use { r ->
            if (r.code == HTTP_UNAUTHORIZED || r.code == HTTP_FORBIDDEN) return@use null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            read(r.body.string().take(MAX_SMALL_ANSWER))
        }
    }

    /** A big call, read as it arrives. */
    private fun <T> streamed(
        params: Map<String, String>,
        read: (JsonReader) -> T?,
    ): T = withSession { api, token ->
        http.newCall(request(url(api, params), token)).execute().use { r ->
            if (r.code == HTTP_UNAUTHORIZED || r.code == HTTP_FORBIDDEN) return@use null
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            readJs(r, read)
        }
    }

    /** Runs [block] with the session; a null answer (the portal stopped taking the token) signs in again once. */
    private fun <T> withSession(block: (api: String, token: String) -> T?): T {
        repeat(2) { round ->
            if (round > 0 || token == null) {
                if (!signInQuietly()) throw IOException("The portal didn't accept this MAC address")
            }
            val answer = block(api ?: return@repeat, token ?: return@repeat)
            if (answer != null) return answer
        }
        throw IOException("The portal didn't accept this MAC address")
    }

    private fun signInQuietly(): Boolean = try {
        signIn()
    } catch (_: IOException) {
        false
    }

    private fun <T> readJs(
        response: Response,
        read: (JsonReader) -> T?,
    ): T? = try {
        JsonReader(response.body.charStream()).use { reader ->
            reader.isLenient = true
            if (reader.peek() != JsonToken.BEGIN_OBJECT) return@use null
            reader.beginObject()
            var result: T? = null
            while (reader.hasNext()) {
                if (reader.nextName() == "js" && reader.peek() != JsonToken.NULL) result = read(reader) else reader.skipValue()
            }
            result
        }
    } catch (_: MalformedJsonException) {
        // "Authorization failed." and other plain-text answers: not JSON, so the token is fetched again.
        null
    } catch (_: EOFException) {
        null // an empty answer, the same
    } catch (e: IllegalStateException) {
        throw IOException("The portal's answer couldn't be read", e)
    } catch (e: NumberFormatException) {
        throw IOException("The portal's answer couldn't be read", e)
    }

    private fun readChannels(
        reader: JsonReader,
        genres: Map<String, String>,
    ): List<Stalker.Channel> {
        val list = ArrayList<Stalker.Channel>()
        // {"js": {"data": [...]}} on most portals, {"js": [...]} on some.
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                readChannelArray(reader, genres, list)
            }

            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "data" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        readChannelArray(reader, genres, list)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }

            else -> {
                reader.skipValue()
            }
        }
        return list
    }

    private fun readChannelArray(
        reader: JsonReader,
        genres: Map<String, String>,
        into: MutableList<Stalker.Channel>,
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
            } else {
                readChannel(readFlat(reader, CHANNEL_FIELDS), genres)?.let(into::add)
            }
        }
        reader.endArray()
    }

    private fun readChannel(
        fields: Map<String, String>,
        genres: Map<String, String>,
    ): Stalker.Channel? {
        val id = fields["id"] ?: return null
        val name = fields["name"]?.takeIf { it.isNotBlank() } ?: return null
        val cmd = fields["cmd"]?.takeIf { it.isNotBlank() } ?: return null
        return Stalker.Channel(
            id = id,
            name = name,
            cmd = cmd,
            logo = Stalker.logo(portal, fields["logo"]),
            genre = genres[fields["tv_genre_id"]].orEmpty(),
            xmltvId = fields["xmltv_id"],
            adult = fields["censored"] == "1",
        )
    }

    /** `{"data": {"<channel id>": [programme, …], …}}`. */
    private fun readGuide(
        reader: JsonReader,
        each: (Programme) -> Unit,
    ): Unit? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return Unit
        }
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "data" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                readGuideChannels(reader, each)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return Unit
    }

    private fun readGuideChannels(
        reader: JsonReader,
        each: (Programme) -> Unit,
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            val channel = reader.nextName()
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    reader.skipValue()
                } else {
                    programme(channel, readFlat(reader, GUIDE_FIELDS))?.let(each)
                }
            }
            reader.endArray()
        }
        reader.endObject()
    }

    private fun programme(
        channel: String,
        f: Map<String, String>,
    ): Programme? {
        val start = f["start_timestamp"]?.toLongOrNull() ?: return null
        val stop = f["stop_timestamp"]?.toLongOrNull() ?: return null
        val title = f["name"]?.takeIf { it.isNotBlank() } ?: return null
        return Programme(channel, Stalker.millis(start), Stalker.millis(stop), title, f["descr"]?.takeIf { it.isNotBlank() })
    }

    /** One object's plain values, of the [wanted] keys only; anything nested is skipped. */
    private fun readFlat(
        reader: JsonReader,
        wanted: Set<String>,
    ): Map<String, String> {
        val out = HashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (key !in wanted) {
                reader.skipValue()
                continue
            }
            when (reader.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> out[key] = reader.nextString()
                JsonToken.BOOLEAN -> out[key] = if (reader.nextBoolean()) "1" else "0"
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return out
    }

    private fun url(
        api: String,
        params: Map<String, String>,
    ): String = api
        .toHttpUrl()
        .newBuilder()
        .apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("JsHttpRequest", "1-xml")
        }.build()
        .toString()

    private fun request(
        url: String,
        token: String?,
    ): Request = Request
        .Builder()
        .url(url)
        .header("User-Agent", Stalker.USER_AGENT)
        .header("X-User-Agent", Stalker.X_USER_AGENT)
        .header("Cookie", Stalker.cookie(mac))
        .header("Referer", Stalker.normalizePortal(portal) + "/c/")
        .apply { token?.let { header("Authorization", "Bearer $it") } }
        .build()

    private companion object {
        const val MAX_SMALL_ANSWER = 512 * 1024
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        val CHANNEL_FIELDS = setOf("id", "name", "cmd", "logo", "tv_genre_id", "xmltv_id", "censored")
        val GUIDE_FIELDS = setOf("name", "descr", "start_timestamp", "stop_timestamp")

        /** The `js` part of a small answer, or null when there is none (a refused token answers in plain text). */
        fun js(body: String): JsonElement? = try {
            (Json.parseToJsonElement(body) as? JsonObject)?.get("js")
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
