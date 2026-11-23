package com.tunedplayer.app.data

import android.content.Context
import androidx.core.content.edit
import com.tunedplayer.app.core.Simkl
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The person's Simkl account, signed in with Simkl's OAuth 2 device code: Settings shows a short code and
 * simkl.com/pin, which works the same from a phone or a TV remote, and Tuned never sees the Simkl password. The tokens
 * are kept encrypted ([KeystoreBox]) and stay out of Android backups, like the login. Without the app's key in the
 * build ([BuildConfig.SIMKL_CLIENT_ID]) none of it shows.
 */
class SimklAccount(
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
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int,
    ) {
        /** Where to open or type the code: the complete URL when Simkl gives one, else the pin page. */
        val verificationUrl: String get() = verificationUriComplete?.takeIf { it.isNotBlank() } ?: verificationUri
    }

    @Serializable
    private data class TokenAnswer(
        @SerialName("access_token") val access: String,
        @SerialName("refresh_token") val refresh: String? = null,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class PollError(
        val error: String? = null,
    )

    private val prefs = context.getSharedPreferences("tuned.simkl", Context.MODE_PRIVATE)
    private val box = KeystoreBox("tuned.simkl")
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshing = Mutex()
    private val userAgent = "Tuned/${BuildConfig.VERSION_NAME}"

    /** Whether Simkl can be used: the build has the key and the admin's `simkl` switch is on (docs/decisions/admin.md). */
    val available: Boolean get() = BuildConfig.SIMKL_CLIENT_ID.isNotBlank() && ServerFlags.now.value.simkl

    private val sessionFlow = MutableStateFlow(load())
    val session: StateFlow<Session?> = sessionFlow.asStateFlow()

    private val scrobbleFlow = MutableStateFlow(prefs.getBoolean(KEY_SCROBBLE, true))

    /** Settings › Simkl › Scrobble what you watch: on by default once signed in. */
    val scrobbleOn: StateFlow<Boolean> = scrobbleFlow.asStateFlow()

    /**
     * Takes the session another device synced ([SyncCopyRepository]) when it is newer than this one's, so signing in
     * to Simkl once covers every device. A device whose session is missing from the copy keeps its own: signing out on
     * one device, or one device's refresh failing, never signs the others out.
     */
    fun adopt(synced: Session) {
        val mine = sessionFlow.value
        if (mine == null || synced.expiresAt > mine.expiresAt) store(synced)
    }

    fun setScrobble(on: Boolean) {
        prefs.edit { putBoolean(KEY_SCROBBLE, on) }
        scrobbleFlow.value = on
    }

    /** Step one of signing in: the code to show and where to enter it. */
    suspend fun requestCode(): DeviceCode? {
        val answer = form("/oauth2/device", Simkl.deviceCodeBody(BuildConfig.SIMKL_CLIENT_ID)) ?: return null
        if (answer.first != HTTP_OK) return null
        return attempt(TAG, "Couldn't read Simkl's sign-in code") {
            json.decodeFromString(DeviceCode.serializer(), answer.second)
        }.getOrNull()
    }

    /**
     * Step two: asks Simkl every few seconds whether the code was entered, until it was or the code ran out. Simkl
     * never signals a refusal, so walking away and saying no both end as expired. Cancelling the coroutine (closing
     * the dialog) stops it.
     */
    suspend fun awaitSignIn(code: DeviceCode): Simkl.Poll {
        var wait = code.interval.coerceAtLeast(1) * MS
        val until = System.currentTimeMillis() + code.expiresIn * MS
        val fields = Simkl.deviceTokenBody(BuildConfig.SIMKL_CLIENT_ID, code.deviceCode)
        while (System.currentTimeMillis() < until) {
            delay(wait)
            val answer = form("/oauth2/token", fields)
            val error =
                answer?.second?.let {
                    attempt(TAG, "Couldn't read Simkl's poll answer") {
                        json.decodeFromString(PollError.serializer(), it).error
                    }.getOrNull()
                }
            when (val poll = answer?.let { Simkl.poll(it.first, error) } ?: Simkl.Poll.WAIT) {
                Simkl.Poll.SIGNED_IN -> return if (saveTokens(requireNotNull(answer).second)) poll else Simkl.Poll.FAILED
                Simkl.Poll.SLOW_DOWN -> wait += SLOW_DOWN_MS
                Simkl.Poll.WAIT -> Unit
                else -> return poll
            }
        }
        return Simkl.Poll.EXPIRED
    }

    /**
     * Signing out of Tuned: forgets the account and its settings on this device. Simkl isn't asked to drop the token,
     * since sync shares the same sign-in with the person's other devices, and revoking it would sign them out too.
     */
    fun disconnect() {
        prefs.edit(commit = true) { clear() }
        sessionFlow.value = null
        scrobbleFlow.value = true
    }

    /** Forgets the account here and tells Simkl to drop the token. */
    suspend fun signOut() {
        val s = sessionFlow.value ?: return
        clear()
        form("/oauth2/revoke", Simkl.revokeBody(BuildConfig.SIMKL_CLIENT_ID, s.refresh))
    }

    /**
     * Tells Simkl a film or episode started, paused or stopped ([action] is `start`, `pause` or `stop`) at [progress]
     * percent. Nothing is sent when signed out or scrobbling is off; a failure is logged and forgotten, since the next
     * play or pause sends the state again.
     */
    suspend fun scrobble(
        action: String,
        target: Simkl.Target,
        progress: Double,
    ) {
        if (!available || !scrobbleFlow.value) return
        val token = token() ?: return
        val body =
            buildJsonObject {
                when (target) {
                    is Simkl.Target.Movie -> {
                        putJsonObject("movie") {
                            put("title", target.title)
                            target.year?.let { put("year", it) }
                        }
                    }

                    is Simkl.Target.Episode -> {
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
        val answer = postJson("/scrobble/$action", body, token)
        if (answer != null && answer.first !in HTTP_OK..HTTP_LAST_OK && answer.first != HTTP_CONFLICT) {
            AppLog.w(TAG, "Simkl didn't take the $action at $progress% (HTTP ${answer.first}): ${answer.second.take(MAX_ERROR)}")
        }
    }

    /** A token that is good for at least a day, renewed first if it isn't. */
    private suspend fun token(): String? = refreshing.withLock {
        val s = sessionFlow.value ?: return@withLock null
        if (!Simkl.needsRefresh(s.expiresAt, System.currentTimeMillis())) return@withLock s.access
        val answer = form("/oauth2/token", Simkl.refreshBody(BuildConfig.SIMKL_CLIENT_ID, s.refresh)) ?: return@withLock null
        when {
            answer.first == HTTP_OK && saveTokens(answer.second, s.username, s.refresh) -> {
                sessionFlow.value?.access
            }

            answer.first == HTTP_UNAUTHORIZED || answer.first == HTTP_BAD_REQUEST -> {
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
        previousRefresh: String? = null,
    ): Boolean {
        val t =
            attempt(TAG, "Couldn't read Simkl's tokens") { json.decodeFromString(TokenAnswer.serializer(), text) }.getOrNull()
                ?: return false
        val refresh = t.refresh?.takeIf { it.isNotBlank() } ?: previousRefresh ?: return false
        val session = Session(t.access, refresh, System.currentTimeMillis() + t.expiresIn * MS, username)
        store(session)
        if (username == null) store(session.copy(username = fetchUsername(session.access)))
        return true
    }

    private suspend fun fetchUsername(token: String): String? {
        val answer = get("/users/settings", token) ?: return null
        if (answer.first != HTTP_OK) return null
        return attempt(TAG, "Couldn't read the Simkl account name") {
            json
                .parseToJsonElement(answer.second)
                .jsonObject["user"]
                ?.jsonObject
                ?.get("name")
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
        return attempt(TAG, "Couldn't read the saved Simkl account") {
            json.decodeFromString(Session.serializer(), box.open(blob))
        }.getOrNull()
    }

    private suspend fun form(
        path: String,
        fields: Map<String, String>,
    ): Pair<Int, String>? {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return send(request(path, token = null).post(body).build())
    }

    private suspend fun postJson(
        path: String,
        body: JsonObject,
        token: String,
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
        val url =
            (Simkl.API + path)
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client_id", BuildConfig.SIMKL_CLIENT_ID)
                .addQueryParameter("app-name", Simkl.APP_NAME)
                .addQueryParameter("app-version", BuildConfig.VERSION_NAME)
                .build()
        val builder =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", userAgent)
        if (token != null) builder.header("Authorization", "Bearer $token")
        return builder
    }

    /** The answer's status and body, or null when the network failed. Tokens and codes are never logged. */
    private suspend fun send(request: Request): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r -> r.code to r.body.string() }
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't reach Simkl", e)
            null
        }
    }

    private companion object {
        const val TAG = "Simkl"
        const val MAX_ERROR = 300
        const val KEY_SESSION = "session"
        const val KEY_SCROBBLE = "scrobble"
        const val MS = 1_000L
        const val SLOW_DOWN_MS = 5_000L
        const val HTTP_OK = 200
        const val HTTP_LAST_OK = 299
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_CONFLICT = 409
        val JSON_TYPE = "application/json".toMediaType()
    }
}
