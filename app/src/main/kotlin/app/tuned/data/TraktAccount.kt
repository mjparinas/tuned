package app.tuned.data

import android.content.Context
import androidx.core.content.edit
import app.tuned.BuildConfig
import app.tuned.core.Trakt
import app.tuned.diagnostics.AppLog
import app.tuned.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The person's Trakt account (from the app audit, item 14), signed in with Trakt's device code: Settings shows a short
 * code and trakt.tv/activate, which works the same from a phone or a TV remote, and Tuned never sees the Trakt
 * password. The tokens are kept encrypted ([KeystoreBox]) and stay out of backups, like the login. Without the app's
 * keys in the build ([BuildConfig.TRAKT_CLIENT_ID]) none of it shows.
 */
class TraktAccount(
    context: Context,
    private val http: OkHttpClient,
) {
    @Serializable
    data class Session(
        val access: String,
        val refresh: String,
        val expiresAt: Long,
        val username: String? = null,
    )

    @Serializable
    data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int,
    )

    @Serializable
    private data class TokenAnswer(
        @SerialName("access_token") val access: String,
        @SerialName("refresh_token") val refresh: String,
        @SerialName("expires_in") val expiresIn: Long,
        @SerialName("created_at") val createdAt: Long,
    )

    private val prefs = context.getSharedPreferences("tuned.trakt", Context.MODE_PRIVATE)
    private val box = KeystoreBox("tuned.trakt")
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshing = Mutex()

    /** Whether this build can talk to Trakt at all. */
    val available: Boolean get() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank()

    private val sessionFlow = MutableStateFlow(load())
    val session: StateFlow<Session?> = sessionFlow.asStateFlow()

    private val scrobbleFlow = MutableStateFlow(prefs.getBoolean(KEY_SCROBBLE, true))

    /** Settings › Trakt › Scrobble what you watch: on by default once signed in. */
    val scrobbleOn: StateFlow<Boolean> = scrobbleFlow.asStateFlow()

    fun setScrobble(on: Boolean) {
        prefs.edit { putBoolean(KEY_SCROBBLE, on) }
        scrobbleFlow.value = on
    }

    /** Step one of signing in: the code to show and where to enter it. */
    suspend fun requestCode(): DeviceCode? {
        val body = buildJsonObject { put("client_id", BuildConfig.TRAKT_CLIENT_ID) }
        val answer = post("/oauth/device/code", body, token = null) ?: return null
        if (answer.first != HTTP_OK) return null
        return attempt(TAG, "Couldn't read Trakt's sign-in code") {
            json.decodeFromString(DeviceCode.serializer(), answer.second)
        }.getOrNull()
    }

    /**
     * Step two: asks Trakt every few seconds whether the code was entered, until it was, the person said no, or the code
     * ran out. Cancelling the coroutine (closing the dialog) stops it.
     */
    suspend fun awaitSignIn(code: DeviceCode): Trakt.Poll {
        var wait = code.interval.coerceAtLeast(1) * MS
        val until = System.currentTimeMillis() + code.expiresIn * MS
        val body = jsonBody(Trakt.deviceTokenBody(BuildConfig.TRAKT_CLIENT_ID, code.deviceCode))
        while (System.currentTimeMillis() < until) {
            delay(wait)
            val answer = post("/oauth/device/token", body, token = null)
            when (val poll = answer?.let { Trakt.poll(it.first) } ?: Trakt.Poll.WAIT) {
                Trakt.Poll.SIGNED_IN -> return if (saveTokens(requireNotNull(answer).second)) poll else Trakt.Poll.FAILED
                Trakt.Poll.SLOW_DOWN -> wait += MS
                Trakt.Poll.WAIT -> Unit
                else -> return poll
            }
        }
        return Trakt.Poll.EXPIRED
    }

    /** Forgets the account here and tells Trakt to drop the token. */
    suspend fun signOut() {
        val s = sessionFlow.value ?: return
        clear()
        post("/oauth/revoke", jsonBody(Trakt.revokeBody(BuildConfig.TRAKT_CLIENT_ID, s.access)), token = null)
    }

    /**
     * Tells Trakt a film or episode started, paused or stopped ([action] is `start`, `pause` or `stop`) at [progress]
     * percent. Nothing is sent when signed out or scrobbling is off; a failure is logged and forgotten, since the next
     * play or pause sends the state again.
     */
    suspend fun scrobble(
        action: String,
        target: Trakt.Target,
        progress: Double,
    ) {
        if (!available || !scrobbleFlow.value) return
        val token = token() ?: return
        val body =
            buildJsonObject {
                when (target) {
                    is Trakt.Target.Movie -> {
                        putJsonObject("movie") {
                            put("title", target.title)
                            target.year?.let { put("year", it) }
                        }
                    }

                    is Trakt.Target.Episode -> {
                        putJsonObject("show") {
                            put("title", target.show)
                            target.year?.let { put("year", it) }
                        }
                        putJsonObject("episode") {
                            put("season", target.season)
                            put("number", target.number)
                        }
                    }
                }
                put("progress", progress)
            }
        val answer = post("/scrobble/$action", body, token)
        if (answer != null && answer.first !in HTTP_OK..HTTP_LAST_OK && answer.first != HTTP_CONFLICT) {
            // Trakt's answer says what it didn't like (it holds no token), so the log can show why.
            AppLog.w(TAG, "Trakt didn't take the $action at $progress% (HTTP ${answer.first}): ${answer.second.take(MAX_ERROR)}")
        }
    }

    /** A token that is good for at least a day, renewed first if it isn't. */
    private suspend fun token(): String? = refreshing.withLock {
        val s = sessionFlow.value ?: return@withLock null
        if (!Trakt.needsRefresh(s.expiresAt, System.currentTimeMillis())) return@withLock s.access
        val body = jsonBody(Trakt.refreshBody(BuildConfig.TRAKT_CLIENT_ID, s.refresh))
        val answer = post("/oauth/token", body, token = null) ?: return@withLock null
        when {
            answer.first == HTTP_OK && saveTokens(answer.second, s.username) -> {
                sessionFlow.value?.access
            }

            answer.first == HTTP_UNAUTHORIZED || answer.first == HTTP_BAD_REQUEST -> {
                // Trakt no longer accepts the account (revoked on trakt.tv): sign out here too.
                clear()
                null
            }

            else -> {
                null
            }
        }
    }

    private suspend fun saveTokens(
        text: String,
        username: String? = null,
    ): Boolean {
        val t =
            attempt(TAG, "Couldn't read Trakt's tokens") { json.decodeFromString(TokenAnswer.serializer(), text) }.getOrNull()
                ?: return false
        val session = Session(t.access, t.refresh, (t.createdAt + t.expiresIn) * MS, username)
        store(session)
        if (username == null) store(session.copy(username = fetchUsername(session.access)))
        return true
    }

    private suspend fun fetchUsername(token: String): String? {
        val answer = get("/users/settings", token) ?: return null
        if (answer.first != HTTP_OK) return null
        return attempt(TAG, "Couldn't read the Trakt account name") {
            json
                .parseToJsonElement(answer.second)
                .jsonObject["user"]
                ?.jsonObject
                ?.get("username")
                ?.jsonPrimitive
                ?.content
        }.getOrNull()
    }

    private fun store(s: Session) {
        prefs.edit { putString(KEY_SESSION, box.seal(json.encodeToString(Session.serializer(), s))) }
        sessionFlow.value = s
    }

    private fun clear() {
        prefs.edit { remove(KEY_SESSION) }
        sessionFlow.value = null
    }

    private fun load(): Session? {
        val blob = prefs.getString(KEY_SESSION, null) ?: return null
        return attempt(TAG, "Couldn't read the saved Trakt account") {
            json.decodeFromString(Session.serializer(), box.open(blob))
        }.getOrNull()
    }

    private fun jsonBody(fields: Map<String, String>): JsonObject = JsonObject(fields.mapValues { JsonPrimitive(it.value) })

    private suspend fun post(
        path: String,
        body: JsonObject,
        token: String?,
    ): Pair<Int, String>? = send(
        request(path, token).post(body.toString().toRequestBody(JSON_TYPE)).build(),
    )

    private suspend fun get(
        path: String,
        token: String,
    ): Pair<Int, String>? = send(request(path, token).get().build())

    private fun request(
        path: String,
        token: String?,
    ): Request.Builder {
        val builder =
            Request
                .Builder()
                .url(Trakt.API + path)
                .header("trakt-api-version", Trakt.API_VERSION)
                .header("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    /** The answer's status and body, or null when the network failed. Tokens and codes are never logged. */
    private suspend fun send(request: Request): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r -> r.code to r.body.string() }
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't reach Trakt", e)
            null
        }
    }

    private companion object {
        const val TAG = "Trakt"
        const val MAX_ERROR = 300
        const val KEY_SESSION = "session"
        const val KEY_SCROBBLE = "scrobble"
        const val MS = 1_000L
        const val HTTP_OK = 200
        const val HTTP_LAST_OK = 299
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_CONFLICT = 409 // the same scrobble twice in a row: already recorded
        val JSON_TYPE = "application/json".toMediaType()
    }
}
