package app.tuned.data

import app.tuned.BuildConfig
import app.tuned.core.ContentKind
import app.tuned.core.OpenSubtitles
import app.tuned.data.db.ItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

/**
 * Subtitle search (Melvin, 2026-11-17): fetches subtitles from OpenSubtitles.com for films and episodes whose stream
 * has none, with the app's key from the OPENSUBTITLES_API_KEY build secret. A build without it asks nothing and the
 * player shows no search button. Every downloaded file is kept on the device by item, so the same film needs no second
 * download (OpenSubtitles allows only a few a day).
 */
class SubtitleRepository(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val credentials: CredentialStore,
    private val metadata: MetadataRepository,
    /** Where downloaded files are kept: app storage, so Android doesn't clear them as a cache. */
    private val folder: File,
) {
    /** Why a search or a download gave nothing. */
    enum class Problem { NO_KEY, BAD_KEY, BAD_ACCOUNT, LIMIT, OFFLINE }

    class Failed(
        val problem: Problem,
        cause: Throwable? = null,
    ) : Exception(problem.name, cause)

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var token: Token? = null

    private data class Token(
        val value: String,
        val host: String,
        val account: SubtitleAccount,
    )

    fun hasKey(): Boolean = key().isNotEmpty()

    /** The search for [item]: a film by title and year, an episode by its show, season and number. */
    suspend fun searchFor(item: ItemEntity): OpenSubtitles.Search {
        val tv = ContentKind.fromCode(item.kind) == ContentKind.SERIES
        // The TMDB id from the details page, when the device has one, matches better than the name; never fetched here.
        val name = if (tv) item.showName ?: item.name else item.name
        val tmdbId = metadata.knownId(name, tv)
        val language =
            settings.settings
                .first()
                .captions.language
        return OpenSubtitles.search(item.name, if (tv) item.showName else null, item.season, item.episode, tmdbId, language)
    }

    /** Matching files, best first; throws [Failed]. */
    suspend fun search(search: OpenSubtitles.Search): List<OpenSubtitles.Result> = withContext(Dispatchers.IO) {
        val apiKey = key().ifEmpty { throw Failed(Problem.NO_KEY) }
        val request = base(apiKey).url(OpenSubtitles.searchUrl(search)).get().build()
        val answer: SearchAnswer = call(request)
        val results =
            answer.data.flatMap { hit ->
                val a = hit.attributes
                a.files.map { f ->
                    OpenSubtitles.Result(
                        fileId = f.fileId,
                        release = a.release?.takeIf { it.isNotBlank() } ?: f.fileName.orEmpty(),
                        language = a.language.orEmpty(),
                        downloads = a.downloadCount,
                        hearingImpaired = a.hearingImpaired,
                        machine = a.machineTranslated || a.aiTranslated,
                        season = a.featureDetails?.seasonNumber,
                        episode = a.featureDetails?.episodeNumber,
                    )
                }
            }
        OpenSubtitles.rank(results, search)
    }

    /** Downloads [result] for [itemId] and keeps it; throws [Failed]. */
    suspend fun download(
        itemId: Long,
        result: OpenSubtitles.Result,
        language: String,
    ): File = withContext(Dispatchers.IO) {
        val apiKey = key().ifEmpty { throw Failed(Problem.NO_KEY) }
        val body = json.encodeToString(DownloadAsk.serializer(), DownloadAsk(result.fileId)).toRequestBody(JSON)
        val session = signIn(apiKey)
        val host = session?.host ?: DEFAULT_HOST
        val request =
            base(apiKey)
                .url("https://$host/api/v1/download")
                .post(body)
                .apply { if (session != null) header("Authorization", "Bearer ${session.value}") }
                .build()
        val answer: DownloadAnswer = call(request)
        val link = answer.link ?: throw Failed(Problem.LIMIT)
        val text =
            fetch(
                Request
                    .Builder()
                    .url(link)
                    .header("User-Agent", userAgent())
                    .build(),
            )
        folder.mkdirs()
        val file = File(folder, OpenSubtitles.fileName(itemId, language))
        val partial = File(folder, file.name + ".part")
        partial.writeBytes(text)
        if (!partial.renameTo(file)) {
            partial.copyTo(file, overwrite = true)
            partial.delete()
        }
        file
    }

    /** The kept files for [ids], so reopening a film shows the subtitles it had, with no download. */
    suspend fun saved(ids: Collection<Long>): Map<Long, File> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val wanted = ids.toSet()
        folder
            .listFiles()
            .orEmpty()
            .mapNotNull { f -> OpenSubtitles.itemOf(f.name)?.takeIf { it in wanted }?.let { it to f } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, files) -> files.maxBy { it.lastModified() } }
    }

    /** Signs in with the optional account once per run (the token lasts a day), or null without one. */
    private fun signIn(apiKey: String): Token? {
        val account = credentials.subtitleAccount() ?: return null
        token?.takeIf { it.account == account }?.let { return it }
        val body = json.encodeToString(LoginAsk.serializer(), LoginAsk(account.username, account.password)).toRequestBody(JSON)
        val answer: LoginAnswer =
            try {
                call(base(apiKey).url(OpenSubtitles.LOGIN_URL).post(body).build())
            } catch (e: Failed) {
                throw if (e.problem == Problem.BAD_KEY) Failed(Problem.BAD_ACCOUNT) else e
            }
        val value = answer.token ?: throw Failed(Problem.BAD_ACCOUNT)
        return Token(value, answer.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST, account).also { token = it }
    }

    private fun key() = BuildConfig.OPENSUBTITLES_API_KEY

    private fun userAgent() = OpenSubtitles.userAgent(BuildConfig.VERSION_NAME)

    private fun base(apiKey: String) = Request
        .Builder()
        .header("Api-Key", apiKey)
        .header("User-Agent", userAgent())
        .header("Accept", "application/json")

    private inline fun <reified T> call(request: Request): T {
        val text = String(fetch(request))
        return try {
            json.decodeFromString<T>(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one: an answer that isn't the JSON expected.
            throw Failed(Problem.OFFLINE, e)
        }
    }

    private fun fetch(request: Request): ByteArray = try {
        http.newCall(request).execute().use { response -> read(response) }
    } catch (e: IOException) {
        throw Failed(Problem.OFFLINE, e)
    }

    private fun read(response: Response): ByteArray {
        if (response.isSuccessful) return response.body.bytes()
        throw Failed(
            when (response.code) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> Problem.BAD_KEY
                HTTP_NOT_ACCEPTABLE, HTTP_TOO_MANY -> Problem.LIMIT
                else -> Problem.OFFLINE
            },
        )
    }

    @Serializable
    private data class SearchAnswer(
        val data: List<Hit> = emptyList(),
    )

    @Serializable
    private data class Hit(
        val attributes: Attributes,
    )

    @Serializable
    private data class Attributes(
        val language: String? = null,
        @SerialName("download_count") val downloadCount: Int = 0,
        @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
        @SerialName("machine_translated") val machineTranslated: Boolean = false,
        @SerialName("ai_translated") val aiTranslated: Boolean = false,
        val release: String? = null,
        @SerialName("feature_details") val featureDetails: Feature? = null,
        val files: List<FileRef> = emptyList(),
    )

    @Serializable
    private data class Feature(
        @SerialName("season_number") val seasonNumber: Int? = null,
        @SerialName("episode_number") val episodeNumber: Int? = null,
    )

    @Serializable
    private data class FileRef(
        @SerialName("file_id") val fileId: Long,
        @SerialName("file_name") val fileName: String? = null,
    )

    @Serializable
    private data class DownloadAsk(
        @SerialName("file_id") val fileId: Long,
        @SerialName("sub_format") val format: String = "srt",
    )

    @Serializable
    private data class DownloadAnswer(
        val link: String? = null,
    )

    @Serializable
    private data class LoginAsk(
        val username: String,
        val password: String,
    )

    @Serializable
    private data class LoginAnswer(
        val token: String? = null,
        @SerialName("base_url") val baseUrl: String? = null,
    )

    private companion object {
        const val DEFAULT_HOST = "api.opensubtitles.com"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_ACCEPTABLE = 406
        const val HTTP_TOO_MANY = 429
        val JSON = "application/json".toMediaType()
    }
}
