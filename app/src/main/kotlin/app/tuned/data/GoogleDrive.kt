package app.tuned.data

import android.content.Context
import androidx.core.content.edit
import app.tuned.BuildConfig
import app.tuned.core.CloudSync
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The person's Google account, for sync between devices (from the app audit, item 13): signed in with Google's code
 * for TVs and other devices, the same on a phone and a TV remote, asking only for Drive's app folder, a hidden folder
 * only Tuned can see. Tuned never sees the Google password. The refresh token is sealed with a Keystore key
 * ([KeystoreBox]) and stays out of backups. Without the app's keys in the build ([BuildConfig.GOOGLE_TV_CLIENT_ID])
 * none of it shows.
 */
class GoogleDrive(
    context: Context,
    private val http: OkHttpClient,
) {
    @Serializable
    data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_url") val verificationUrl: String,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int = DEFAULT_INTERVAL_S,
    )

    @Serializable
    private data class TokenAnswer(
        @SerialName("access_token") val access: String,
        @SerialName("refresh_token") val refresh: String? = null,
        @SerialName("expires_in") val expiresIn: Long,
    )

    /** The synced copy in Drive: its id, and the fingerprint and time stored beside it. */
    data class Remote(
        val id: String,
        val fingerprint: String?,
        val changedAt: Long,
    )

    private val prefs = context.getSharedPreferences("tuned.google", Context.MODE_PRIVATE)
    private val box = KeystoreBox("tuned.google")
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshing = Mutex()
    private var access: String? = null
    private var accessUntil = 0L

    /** Whether this build can talk to Google at all. */
    val available: Boolean get() = BuildConfig.GOOGLE_TV_CLIENT_ID.isNotBlank()

    private val signedInFlow = MutableStateFlow(prefs.contains(KEY_REFRESH))
    val signedIn: StateFlow<Boolean> = signedInFlow.asStateFlow()

    /** Step one of signing in: the code to show and where to enter it. */
    suspend fun requestCode(): DeviceCode? {
        val form =
            FormBody
                .Builder()
                .add("client_id", BuildConfig.GOOGLE_TV_CLIENT_ID)
                .add("scope", CloudSync.SCOPE)
                .build()
        val answer = send(Request.Builder().url(DEVICE_CODE_URL).post(form).build()) ?: return null
        if (answer.first != HTTP_OK) return null
        return attempt(TAG, "Couldn't read Google's sign-in code") {
            json.decodeFromString(DeviceCode.serializer(), answer.second)
        }.getOrNull()
    }

    /** Step two: asks Google every few seconds whether the code was entered. Cancelling the coroutine stops it. */
    suspend fun awaitSignIn(code: DeviceCode): CloudSync.Poll {
        var wait = code.interval.coerceAtLeast(1) * MS
        val until = System.currentTimeMillis() + code.expiresIn * MS
        val form =
            FormBody
                .Builder()
                .add("client_id", BuildConfig.GOOGLE_TV_CLIENT_ID)
                .add("client_secret", BuildConfig.GOOGLE_TV_CLIENT_SECRET)
                .add("device_code", code.deviceCode)
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .build()
        while (System.currentTimeMillis() < until) {
            delay(wait)
            val answer = send(Request.Builder().url(TOKEN_URL).post(form).build())
            val poll = answer?.let { CloudSync.poll(it.first, errorOf(it.second)) } ?: CloudSync.Poll.WAIT
            when (poll) {
                CloudSync.Poll.SIGNED_IN -> return if (saveTokens(requireNotNull(answer).second)) poll else CloudSync.Poll.FAILED
                CloudSync.Poll.SLOW_DOWN -> wait += SLOW_DOWN_MS
                CloudSync.Poll.WAIT -> Unit
                else -> return poll
            }
        }
        return CloudSync.Poll.EXPIRED
    }

    /** Forgets the account here and tells Google to drop the token. */
    suspend fun signOut() {
        val refresh = refreshToken()
        forget()
        if (refresh != null) {
            val form = FormBody.Builder().add("token", refresh).build()
            send(Request.Builder().url(REVOKE_URL).post(form).build())
        }
    }

    /** The synced copy's details, or null when Drive holds none. Throws [IOException] when Drive can't be asked. */
    suspend fun find(): Remote? {
        val url =
            FILES_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("q", "name = '${CloudSync.FILE_NAME}'")
                .addQueryParameter("fields", "files(id,appProperties)")
                .build()
        val answer = authorized(Request.Builder().url(url).get())
        val file =
            json
                .parseToJsonElement(answer)
                .jsonObject["files"]
                ?.let { it as? JsonArray }
                ?.firstOrNull()
                ?.jsonObject ?: return null
        val props = file["appProperties"]?.jsonObject
        return Remote(
            id = requireNotNull(file["id"]).jsonPrimitive.content,
            fingerprint = props?.get(PROP_FINGERPRINT)?.jsonPrimitive?.content,
            changedAt = props?.get(PROP_CHANGED)?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
        )
    }

    /** The sealed copy's bytes. Throws [IOException] when Drive can't be asked. */
    suspend fun download(id: String): ByteArray = call(Request.Builder().url("$FILES_URL/$id?alt=media").get()) { it.body.bytes() }

    /** Puts [sealed] in Drive's app folder, replacing [existing] when there is one. Throws [IOException] on failure. */
    suspend fun upload(
        sealed: ByteArray,
        fingerprint: String,
        changedAt: Long,
        existing: String?,
    ) {
        val metadata =
            buildJsonObject {
                if (existing == null) {
                    put("name", CloudSync.FILE_NAME)
                    putJsonArray("parents") { add(JsonPrimitive("appDataFolder")) }
                }
                putJsonObject("appProperties") {
                    put(PROP_FINGERPRINT, fingerprint)
                    put(PROP_CHANGED, changedAt.toString())
                }
            }
        val body =
            MultipartBody
                .Builder()
                .setType("multipart/related".toMediaType())
                .addPart(metadata.toString().toRequestBody(JSON_TYPE))
                .addPart(sealed.toRequestBody(BYTES_TYPE))
                .build()
        val request =
            if (existing == null) {
                Request.Builder().url("$UPLOAD_URL?uploadType=multipart").post(body)
            } else {
                Request.Builder().url("$UPLOAD_URL/$existing?uploadType=multipart").patch(body)
            }
        authorized(request)
    }

    private suspend fun authorized(request: Request.Builder): String = call(request) { it.body.string() }

    private suspend fun <T> call(
        request: Request.Builder,
        read: (Response) -> T,
    ): T {
        val token = token() ?: throw IOException("Not signed in to Google")
        return withContext(Dispatchers.IO) {
            http.newCall(request.header("Authorization", "Bearer $token").build()).execute().use { r ->
                if (r.code == HTTP_UNAUTHORIZED) access = null
                if (!r.isSuccessful) throw IOException("Google Drive answered HTTP ${r.code}")
                read(r)
            }
        }
    }

    /** An access token good for at least a minute, renewed from the refresh token when it isn't. */
    private suspend fun token(): String? = refreshing.withLock {
        access?.takeIf { System.currentTimeMillis() < accessUntil - MINUTE_MS }?.let { return@withLock it }
        val refresh = refreshToken() ?: return@withLock null
        val form =
            FormBody
                .Builder()
                .add("client_id", BuildConfig.GOOGLE_TV_CLIENT_ID)
                .add("client_secret", BuildConfig.GOOGLE_TV_CLIENT_SECRET)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build()
        val answer = send(Request.Builder().url(TOKEN_URL).post(form).build()) ?: return@withLock null
        when {
            answer.first == HTTP_OK && saveTokens(answer.second) -> {
                access
            }

            errorOf(answer.second) == "invalid_grant" -> {
                // Google no longer accepts the account (access removed in the Google account): sign out here too.
                forget()
                null
            }

            else -> {
                null
            }
        }
    }

    private fun saveTokens(text: String): Boolean {
        val t =
            attempt(TAG, "Couldn't read Google's tokens") { json.decodeFromString(TokenAnswer.serializer(), text) }.getOrNull()
                ?: return false
        access = t.access
        accessUntil = System.currentTimeMillis() + t.expiresIn * MS
        t.refresh?.let { refresh ->
            prefs.edit { putString(KEY_REFRESH, box.seal(refresh)) }
            signedInFlow.value = true
        }
        return true
    }

    private fun refreshToken(): String? {
        val blob = prefs.getString(KEY_REFRESH, null) ?: return null
        return attempt(TAG, "Couldn't read the saved Google account") { box.open(blob) }.getOrNull()
    }

    private fun forget() {
        prefs.edit { remove(KEY_REFRESH) }
        access = null
        signedInFlow.value = false
    }

    private fun errorOf(body: String): String? = attempt(TAG, "Couldn't read Google's answer") {
        json
            .parseToJsonElement(body)
            .jsonObject["error"]
            ?.jsonPrimitive
            ?.content
    }.getOrNull()

    /** The answer's status and body, or null when the network failed. Tokens and codes are never logged. */
    private suspend fun send(request: Request): Pair<Int, String>? = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { r -> r.code to r.body.string() }
        } catch (e: IOException) {
            AppLog.w(TAG, "Couldn't reach Google", e)
            null
        }
    }

    private companion object {
        const val TAG = "GoogleDrive"
        const val KEY_REFRESH = "refresh"
        const val PROP_FINGERPRINT = "fingerprint"
        const val PROP_CHANGED = "changedAt"
        const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
        const val DEFAULT_INTERVAL_S = 5
        const val MS = 1_000L
        const val SLOW_DOWN_MS = 5_000L
        const val MINUTE_MS = 60_000L
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        val JSON_TYPE = "application/json; charset=UTF-8".toMediaType()
        val BYTES_TYPE = "application/octet-stream".toMediaType()
    }
}
