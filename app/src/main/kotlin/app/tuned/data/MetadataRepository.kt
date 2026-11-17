package app.tuned.data

import app.tuned.core.Tmdb
import app.tuned.data.db.MetadataDao
import app.tuned.data.db.MetadataEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Someone in the cast, with the part they play. [id] is TMDB's, null for cast saved before it was kept. */
data class CastMember(
    val name: String,
    val character: String?,
    val photo: String?,
    val id: Long? = null,
)

/** A film or show as The Movie Database describes it. */
data class Metadata(
    val title: String,
    val year: String?,
    val overview: String?,
    /** Out of 10, or null when too few people have voted. */
    val rating: Double?,
    val poster: String?,
    val backdrop: String?,
    val genres: List<String>,
    val runtime: String?,
    val cast: List<CastMember>,
    /** A YouTube video id. */
    val trailer: String?,
)

/**
 * Looks films and shows up on TMDB with the key from Settings and keeps every answer in the database, so a page or
 * a rating seen once loads from the device after that (Melvin, 2026-11-17). Answers are refreshed after a month,
 * and "nothing found" after a week. Without a key nothing is asked.
 */
class MetadataRepository(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val dao: MetadataDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** One lookup per title at a time, so a grid asking for the same poster twice makes one request. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun hasKey(): Boolean = settings.settings
        .first()
        .tmdbKey
        .isNotBlank()

    /** What the device already knows, without asking the network. */
    suspend fun cached(
        name: String,
        tv: Boolean,
    ): Metadata? = dao.get(cacheKey(name, tv))?.takeIf { it.found }?.toMetadata()

    /** Rating, summary and artwork: one search, cached. Enough for a poster's rating badge. */
    suspend fun summary(
        name: String,
        tv: Boolean,
    ): Metadata? = lookup(name, tv, full = false)

    /** Everything for the details page: the summary plus genres, runtime, cast and trailer, cached. */
    suspend fun details(
        name: String,
        tv: Boolean,
    ): Metadata? = lookup(name, tv, full = true)

    private suspend fun lookup(
        name: String,
        tv: Boolean,
        full: Boolean,
    ): Metadata? {
        val key = cacheKey(name, tv)
        return locks.getOrPut(key) { Mutex() }.withLock {
            val row = dao.get(key)
            val now = System.currentTimeMillis()
            val fresh = row != null && now - row.fetchedAt < if (row.found) KEEP_MS else KEEP_MISSING_MS
            when {
                row != null && fresh && (!row.found || row.full || !full) -> row.takeIf { it.found }?.toMetadata()
                else -> fetch(key, name, tv, full, row)
            }
        }
    }

    private suspend fun fetch(
        cacheKey: String,
        name: String,
        tv: Boolean,
        full: Boolean,
        old: MetadataEntity?,
    ): Metadata? {
        val apiKey =
            settings.settings
                .first()
                .tmdbKey
                .trim()
        if (apiKey.isEmpty()) return old?.takeIf { it.found }?.toMetadata()
        val row =
            try {
                withContext(Dispatchers.IO) { download(cacheKey, Tmdb.query(name), tv, full, apiKey) }
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            } ?: return old?.takeIf { it.found }?.toMetadata() // offline: what the device had
        dao.upsert(row)
        return row.takeIf { it.found }?.toMetadata()
    }

    private fun download(
        cacheKey: String,
        query: Tmdb.Query,
        tv: Boolean,
        full: Boolean,
        key: String,
    ): MetadataEntity {
        val now = System.currentTimeMillis()
        val hit =
            get<Results<Hit>>(Tmdb.search(query, tv, key), key).results.firstOrNull()
                ?: return MetadataEntity(cacheKey, false, false, 0, query.title, null, null, null, null, null, "", null, "", null, now)
        val more = if (full) get<Details>(Tmdb.details(hit.id, tv, key), key) else null
        val videos =
            more
                ?.videos
                ?.results
                .orEmpty()
                .map { Tmdb.Video(it.site, it.type, it.key, it.official) }
        return MetadataEntity(
            cacheKey = cacheKey,
            found = true,
            full = full,
            tmdbId = hit.id,
            title = hit.title ?: hit.name ?: query.title,
            year = (hit.releaseDate ?: hit.firstAirDate)?.take(4)?.takeIf { it.length == 4 },
            overview = hit.overview?.takeIf { it.isNotBlank() },
            rating = hit.voteAverage?.takeIf { (hit.voteCount ?: 0) >= MIN_VOTES && it > 0 },
            poster = hit.posterPath?.let { Tmdb.IMAGES + it },
            backdrop = hit.backdropPath?.let { Tmdb.BACKDROPS + it },
            genres = more?.genres.orEmpty().joinToString(",") { it.name },
            runtime = Tmdb.runtime(more?.runtime ?: more?.episodeRunTime?.firstOrNull()),
            cast =
                more
                    ?.credits
                    ?.cast
                    .orEmpty()
                    .take(MAX_CAST)
                    .joinToString("\n") { c ->
                        listOf(
                            c.name.clean(),
                            c.character.orEmpty().clean(),
                            c.profilePath?.let { Tmdb.PROFILES + it }.orEmpty(),
                            c.id?.toString().orEmpty(),
                        )
                            .joinToString("\t")
                    },
            trailer = Tmdb.trailer(videos),
            fetchedAt = now,
        )
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ')

    private fun MetadataEntity.toMetadata() = Metadata(
        title = title,
        year = year,
        overview = overview,
        rating = rating,
        poster = poster,
        backdrop = backdrop,
        genres = genres.split(',').filter { it.isNotBlank() },
        runtime = runtime,
        cast =
            cast.split('\n').filter { it.isNotBlank() }.map { line ->
                val p = line.split('\t')
                CastMember(p[0], p.getOrNull(1)?.ifBlank { null }, p.getOrNull(2)?.ifBlank { null }, p.getOrNull(3)?.toLongOrNull())
            },
        trailer = trailer,
    )

    private fun cacheKey(
        name: String,
        tv: Boolean,
    ) = "${if (tv) "tv" else "movie"}|$name"

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
        @SerialName("backdrop_path") val backdropPath: String? = null,
    )

    @Serializable
    private data class Details(
        val genres: List<Genre> = emptyList(),
        val runtime: Int? = null,
        @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
        val videos: Results<VideoJson>? = null,
        val credits: Credits? = null,
    )

    @Serializable
    private data class Genre(
        val name: String,
    )

    @Serializable
    private data class Credits(
        val cast: List<Person> = emptyList(),
    )

    @Serializable
    private data class Person(
        val id: Long? = null,
        val name: String,
        val character: String? = null,
        @SerialName("profile_path") val profilePath: String? = null,
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
        const val MAX_CAST = 15
        const val KEEP_MS = 30L * 24 * 3_600_000
        const val KEEP_MISSING_MS = 7L * 24 * 3_600_000
    }
}
