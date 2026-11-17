package app.tuned.data

import app.tuned.core.Filmography
import app.tuned.core.Tmdb
import app.tuned.data.db.MetadataDao
import app.tuned.data.db.MetadataEntity
import app.tuned.diagnostics.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** An actor's page: who they are and everything they were in, oldest first. */
data class Person(
    val id: Long,
    val name: String,
    val photo: String?,
    val biography: String?,
    val credits: List<Filmography.Credit>,
)

/**
 * Actors' filmographies from TMDB, one request per person (TMDB sends every credit in one answer, so there are no
 * pages to fetch). Each answer is kept in the `metadata` table under "person|<id>", so a page seen once opens
 * from the device, offline too, and is refreshed after a month.
 */
class PeopleRepository(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val dao: MetadataDao,
    /** False while the device is offline: the cached page is shown and TMDB isn't asked. */
    private val online: () -> Boolean = { true },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** What the device already has, without asking the network. */
    suspend fun cached(id: Long): Person? = dao.get(cacheKey(id))?.takeIf { it.found }?.toPerson()

    /** The cached page while it's fresh, otherwise TMDB's latest; the cached page again when TMDB can't be reached. */
    suspend fun load(id: Long): Person? {
        val row = dao.get(cacheKey(id))
        if (row != null && System.currentTimeMillis() - row.fetchedAt < KEEP_MS) return row.takeIf { it.found }?.toPerson()
        val key = apiKey()?.takeIf { online() } ?: return row?.takeIf { it.found }?.toPerson()
        val fresh = fetch { download(id, key) } ?: return row?.takeIf { it.found }?.toPerson()
        dao.upsert(fresh)
        return fresh.toPerson()
    }

    /** TMDB's id for someone in a cast saved before ids were kept: the first person of that name. */
    suspend fun idFor(name: String): Long? {
        val key = apiKey()?.takeIf { online() } ?: return null
        return fetch { get<Results<Found>>(Tmdb.searchPerson(name, key), key).results.firstOrNull()?.id }
    }

    private suspend fun apiKey(): String? = settings.settings
        .first()
        .tmdbKey
        .trim()
        .ifEmpty { null }

    private suspend fun <T> fetch(block: () -> T): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            AppLog.w(TAG, "TMDB person lookup failed", e)
            null
        } catch (e: SerializationException) {
            AppLog.w(TAG, "TMDB person answer unreadable", e)
            null
        }
    }

    private fun download(
        id: Long,
        key: String,
    ): MetadataEntity {
        val answer = get<PersonJson>(Tmdb.person(id, key), key)
        val credits =
            answer.combinedCredits
                ?.cast
                .orEmpty()
                .filter { it.mediaType == "movie" || it.mediaType == "tv" }
                .map { c ->
                    val tv = c.mediaType == "tv"
                    StoredCredit(
                        id = c.id,
                        tv = tv,
                        title = (if (tv) c.name else c.title) ?: c.title ?: c.name.orEmpty(),
                        date = (if (tv) c.firstAirDate else c.releaseDate)?.ifBlank { null },
                        character = c.character?.ifBlank { null },
                        poster = c.posterPath?.let { Tmdb.IMAGES + it },
                        episodes = c.episodeCount?.takeIf { tv && it > 0 },
                        genres = c.genreIds,
                    )
                }
        return MetadataEntity(
            cacheKey = cacheKey(id),
            found = true,
            full = true,
            tmdbId = id,
            title = answer.name,
            year = null,
            overview = answer.biography?.ifBlank { null },
            rating = null,
            poster = answer.profilePath?.let { Tmdb.PROFILES + it },
            backdrop = null,
            genres = "",
            runtime = null,
            cast = json.encodeToString(ListSerializer(StoredCredit.serializer()), credits),
            trailer = null,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun MetadataEntity.toPerson(): Person {
        val stored =
            try {
                json.decodeFromString<List<StoredCredit>>(cast)
            } catch (e: SerializationException) {
                AppLog.w(TAG, "Cached credits unreadable", e)
                emptyList()
            } catch (e: IllegalArgumentException) {
                AppLog.w(TAG, "Cached credits unreadable", e)
                emptyList()
            }
        val credits = stored.map { Filmography.Credit(it.id, it.tv, it.title, it.date, it.character, it.poster, it.episodes, it.genres) }
        return Person(tmdbId, title, poster, overview, Filmography.order(credits))
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

    /** One credit as the device keeps it. */
    @Serializable
    private data class StoredCredit(
        val id: Long,
        val tv: Boolean,
        val title: String,
        val date: String? = null,
        val character: String? = null,
        val poster: String? = null,
        val episodes: Int? = null,
        val genres: List<Int> = emptyList(),
    )

    @Serializable
    private data class Results<T>(
        val results: List<T> = emptyList(),
    )

    @Serializable
    private data class Found(
        val id: Long,
    )

    @Serializable
    private data class PersonJson(
        val name: String,
        val biography: String? = null,
        @SerialName("profile_path") val profilePath: String? = null,
        @SerialName("combined_credits") val combinedCredits: CreditsJson? = null,
    )

    @Serializable
    private data class CreditsJson(
        val cast: List<CreditJson> = emptyList(),
    )

    @Serializable
    private data class CreditJson(
        val id: Long,
        @SerialName("media_type") val mediaType: String? = null,
        val title: String? = null,
        val name: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        val character: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("episode_count") val episodeCount: Int? = null,
        @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    )

    private companion object {
        const val TAG = "People"
        const val KEEP_MS = 30L * 24 * 3_600_000

        fun cacheKey(id: Long) = "person|$id"
    }
}
