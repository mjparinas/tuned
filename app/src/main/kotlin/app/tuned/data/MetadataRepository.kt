package app.tuned.data

import app.tuned.core.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A film or show as The Movie Database describes it. */
data class Metadata(
    val title: String,
    val year: String?,
    val overview: String?,
    /** Out of 10, or null when too few people have voted. */
    val rating: Double?,
    val poster: String?,
    /** A YouTube video id. */
    val trailer: String?,
)

/**
 * Looks films and shows up on TMDB with the key from Settings. Nothing is asked without a key, answers are kept for
 * the session, and a failed lookup just means no extra details.
 */
class MetadataRepository(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
) {
    private val cache = ConcurrentHashMap<String, Metadata>()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hasKey(): Boolean = settings.settings
        .first()
        .tmdbKey
        .isNotBlank()

    suspend fun lookup(
        name: String,
        tv: Boolean,
    ): Metadata? {
        val key =
            settings.settings
                .first()
                .tmdbKey
                .trim()
        if (key.isEmpty()) return null
        val cacheKey = "$tv|$name"
        cache[cacheKey]?.let { return it }
        return try {
            withContext(Dispatchers.IO) { fetch(Tmdb.query(name), tv, key) }?.also { cache[cacheKey] = it }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        }
    }

    private fun fetch(
        query: Tmdb.Query,
        tv: Boolean,
        key: String,
    ): Metadata? {
        val hit = get<Results<Hit>>(Tmdb.search(query, tv, key), key).results.firstOrNull() ?: return null
        val videos =
            runCatching { get<Results<VideoJson>>(Tmdb.videos(hit.id, tv, key), key).results }
                .getOrDefault(emptyList())
                .map { Tmdb.Video(it.site, it.type, it.key, it.official) }
        return Metadata(
            title = hit.title ?: hit.name ?: query.title,
            year = (hit.releaseDate ?: hit.firstAirDate)?.take(4)?.takeIf { it.length == 4 },
            overview = hit.overview?.takeIf { it.isNotBlank() },
            rating = hit.voteAverage?.takeIf { (hit.voteCount ?: 0) >= MIN_VOTES && it > 0 },
            poster = hit.posterPath?.let { Tmdb.IMAGES + it },
            trailer = Tmdb.trailer(videos),
        )
    }

    private inline fun <reified T> get(
        url: String,
        key: String,
    ): T {
        val request =
            Request
                .Builder()
                .url(url)
                .apply { if (Tmdb.isToken(key)) header("Authorization", "Bearer $key") }
                .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TMDB answered ${response.code}")
            return json.decodeFromString(response.body.string())
        }
    }

    @Serializable
    private data class Results<T>(
        val results: List<T> = emptyList(),
    )

    @Serializable
    private data class Hit(
        val id: Long,
        val title: String? = null,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        @SerialName("vote_average") val voteAverage: Double? = null,
        @SerialName("vote_count") val voteCount: Int? = null,
        @SerialName("poster_path") val posterPath: String? = null,
    )

    @Serializable
    private data class VideoJson(
        val site: String? = null,
        val type: String? = null,
        val key: String? = null,
        val official: Boolean = false,
    )

    private companion object {
        const val MIN_VOTES = 10
    }
}
