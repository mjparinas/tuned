package com.tunedplayer.app.data

import android.content.Context
import androidx.core.content.edit
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.core.AccountSeats
import com.tunedplayer.app.core.DeviceSeats
import com.tunedplayer.app.core.SeatDevice
import com.tunedplayer.app.diagnostics.AppLog
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.premium.DeviceSeatsClient
import com.tunedplayer.app.premium.SeatPurchase
import com.tunedplayer.app.work.TvHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * What the account says about Premium on its devices (docs/decisions/accounts.md): this device's [seat] ("active",
 * "full", "none" or "released"), the Premium kind ("lifetime", "yearly" or null), how many devices it covers, how
 * many more seats it can take, the devices holding seats, and which reported purchases ([recorded], as hashes of
 * their tokens) it holds.
 */
data class AccountPlan(
    val seat: String,
    val premium: String?,
    val limit: Int,
    val canBuy: Int,
    val devices: List<SeatDevice>,
    val recorded: List<String>,
    val ownsYearlySeat: Boolean,
)

/**
 * The person's Tuned account (docs/decisions/accounts.md): signed in with Google on Tuned's website, which hands this
 * device a one-time code through the TV sign-in relay ([TvRelay]); the device swaps it for its own token, sealed with a
 * Keystore key ([KeystoreBox]). The account holds the backup that keeps a phone and a TV the same, as plain JSON (Melvin,
 * 2026-11-19: "i dont want to encrypt backups"). Removing this device on the website signs it out here on its next call.
 */
class TunedAccount(
    context: Context,
    private val http: OkHttpClient,
    /** Tuned's server, the same one as the TV sign-in relay. */
    private val base: () -> String,
) {
    /** The account's backup: its version, and the fingerprint and time this app stored with it. */
    class Remote(
        val version: Int,
        val fingerprint: String?,
        val changedAt: Long,
        val copy: ByteArray,
    )

    /** Another device saved a backup since this one last looked: look again. */
    class Conflict : IOException("Another device saved the backup first")

    private val prefs = context.getSharedPreferences("tuned.account", Context.MODE_PRIVATE)
    private val box = KeystoreBox("tuned.account")
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceId = DeviceSeatsClient.deviceId(context)
    private val deviceName = DeviceSeatsClient.deviceName(context)
    private val kind =
        if (TvHome.isTv(context)) {
            "tv"
        } else if (context.resources.configuration.smallestScreenWidthDp >=
            TABLET_DP
        ) {
            "tablet"
        } else {
            "phone"
        }

    private val signedInFlow = MutableStateFlow(prefs.contains(KEY_TOKEN))
    val signedIn: StateFlow<Boolean> = signedInFlow.asStateFlow()
    private val emailFlow = MutableStateFlow(prefs.getString(KEY_EMAIL, null))
    val email: StateFlow<String?> = emailFlow.asStateFlow()

    /** The website's address for signing in the device that shows [code]. */
    fun signInPage(code: String): String = "${base().trimEnd('/')}/account?tv=$code"

    /** Swaps a one-time code from the relay for this device's own token. False when the code ran out or the server is down. */
    suspend fun signIn(link: String): Boolean {
        val ask = TokenAsk(link, deviceId, kind, if (BuildConfig.GITHUB_UPDATES) "github" else "play", deviceName)
        val reply = send(post("api/auth/token", json.encodeToString(TokenAsk.serializer(), ask)))
        val answer =
            reply?.takeIf { it.first == HTTP_OK }?.let { (_, text) ->
                attempt(TAG, "Couldn't read the sign-in answer") { json.decodeFromString(TokenAnswer.serializer(), text) }.getOrNull()
            }
        if (answer == null || answer.token.isBlank()) return false
        prefs.edit(commit = true) {
            putString(KEY_TOKEN, box.seal(answer.token))
            putString(KEY_EMAIL, answer.email)
        }
        emailFlow.value = answer.email
        signedInFlow.value = true
        return true
    }

    /** A one-time code that signs a TV in to this account, to seal into what the phone sends it. Null when signed out. */
    suspend fun newLink(): String? {
        val request = authorized(post("api/auth/link", "{}")) ?: return null
        val (status, text) = send(request) ?: return null
        if (status != HTTP_OK) return null
        return attempt(TAG, "Couldn't read the TV code") { json.decodeFromString(LinkAnswer.serializer(), text).link }.getOrNull()
    }

    /** The backup, or null when the account has none yet. Throws [IOException] when the server can't be asked. */
    suspend fun find(): Remote? {
        val request = signedIn(Request.Builder().url(url("api/backup")).get())
        val (status, text, etag) = call(request)
        if (status == HTTP_NOT_FOUND) return null
        if (status != HTTP_OK) throw IOException("The backup answered $status")
        val stored = json.decodeFromString(Stored.serializer(), text)
        return Remote(
            version = etag?.trim('"')?.toIntOrNull() ?: 0,
            fingerprint = stored.fingerprint,
            changedAt = stored.changedAt,
            copy = stored.copy.toString().toByteArray(),
        )
    }

    /** Saves [plain] (the sync copy, JSON) on top of backup [version] (0 for the first). Throws [Conflict] or [IOException]. */
    suspend fun upload(
        plain: ByteArray,
        fingerprint: String,
        changedAt: Long,
        version: Int,
    ) {
        val stored = Stored(fingerprint, changedAt, json.parseToJsonElement(plain.decodeToString()))
        val body = json.encodeToString(Stored.serializer(), stored).toRequestBody(JSON)
        val request =
            signedIn(
                Request
                    .Builder()
                    .url(url("api/backup"))
                    .header("If-Match", "\"$version\"")
                    .put(body),
            )
        val (status) = call(request)
        when (status) {
            HTTP_OK -> Unit
            HTTP_CONFLICT -> throw Conflict()
            else -> throw IOException("The backup answered $status")
        }
    }

    /**
     * Asks the account for this device's Premium seat ([use] true) or gives it back ([use] false), reporting the
     * store's [purchases] so the account knows what was bought in Play. Null when signed out or the server can't be
     * reached, which never locks anyone out.
     */
    suspend fun seat(
        use: Boolean,
        purchases: List<SeatPurchase>,
    ): AccountPlan? {
        val ask = SeatAsk(use, purchases.map { Bought(it.productId, it.token) })
        return seats(authorized(post("api/account/seat", json.encodeToString(SeatAsk.serializer(), ask))))
    }

    /** Removes another device from the account, which signs it out and frees its seat. Null when it couldn't. */
    suspend fun removeDevice(id: String): AccountPlan? =
        seats(authorized(post("api/account/devices", json.encodeToString(RemoveAsk.serializer(), RemoveAsk(id)))))

    private suspend fun seats(request: Request?): AccountPlan? {
        val (status, text) = send(request ?: return null) ?: return null
        if (status != HTTP_OK) return null
        val reply = attempt(TAG, "Couldn't read the account's seats") { json.decodeFromString(SeatReply.serializer(), text) }.getOrNull()
        return reply?.let {
            AccountPlan(
                seat = it.seat,
                premium = it.premium,
                limit = it.seats.limit,
                canBuy = it.seats.canBuy,
                devices =
                    it.devices
                        .filter { device -> device.seated }
                        .map { device -> SeatDevice(device.id, device.name, device.lastSeen, device.thisDevice) },
                recorded = it.recorded,
                ownsYearlySeat = it.purchases.any { bought -> bought.source == "play" && bought.product == AccountSeats.SEAT_YEARLY },
            )
        }
    }

    /** Signs this device out of the account, which frees its seat. The backup and other devices are untouched. */
    suspend fun signOut() {
        authorized(post("api/auth/signout", "{}"))?.let { send(it) }
        disconnect()
    }

    /** Forgets the account on this device at once, without asking the server (signing out of Tuned, [AppReset]). */
    fun disconnect() {
        prefs.edit(commit = true) { clear() }
        emailFlow.value = null
        signedInFlow.value = false
    }

    private fun url(path: String) = "${base().trimEnd('/')}/$path"

    private fun post(
        path: String,
        body: String,
    ) = Request.Builder().url(url(path)).post(body.toRequestBody(JSON))

    private fun authorized(request: Request.Builder): Request? {
        val sealed = prefs.getString(KEY_TOKEN, null) ?: return null
        val token = attempt(TAG, "Couldn't read the account token") { box.open(sealed) }.getOrNull() ?: return null
        return request.header("Authorization", "Bearer $token").build()
    }

    private fun signedIn(request: Request.Builder): Request = authorized(request) ?: throw IOException("Not signed in")

    private suspend fun send(request: Request.Builder): Pair<Int, String>? = send(request.build())

    private suspend fun send(request: Request): Pair<Int, String>? = attempt(TAG, "Couldn't reach Tuned's server") {
        call(request).let { it.status to it.text }
    }.getOrNull()

    private data class Answer(
        val status: Int,
        val text: String,
        val etag: String?,
    )

    /** One call; a 401 means this device was signed out or removed, so the account is forgotten here too. */
    private suspend fun call(request: Request): Answer = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val answer = Answer(response.code, response.body.string(), response.header("ETag"))
            if (answer.status == HTTP_UNAUTHORIZED && request.header("Authorization") != null) {
                AppLog.w(TAG, "This device was signed out of its Tuned account")
                disconnect()
            }
            answer
        }
    }

    @Serializable
    private data class SeatAsk(
        val use: Boolean,
        val purchases: List<Bought>,
    )

    @Serializable
    private data class Bought(
        val product: String,
        val token: String,
    )

    @Serializable
    private data class RemoveAsk(
        val remove: String,
    )

    @Serializable
    private data class SeatReply(
        val seat: String = "",
        val premium: String? = null,
        val seats: SeatCount = SeatCount(),
        val devices: List<AccountDevice> = emptyList(),
        val purchases: List<Owned> = emptyList(),
        val recorded: List<String> = emptyList(),
    )

    @Serializable
    private data class SeatCount(
        val limit: Int = DeviceSeats.LIMIT,
        val canBuy: Int = 0,
    )

    @Serializable
    private data class AccountDevice(
        val id: String,
        val name: String,
        val seated: Boolean = false,
        val lastSeen: Long = 0,
        val thisDevice: Boolean = false,
    )

    @Serializable
    private data class Owned(
        val source: String,
        val product: String,
    )

    @Serializable
    private data class TokenAsk(
        val link: String,
        val device: String,
        val kind: String,
        val build: String,
        val name: String,
    )

    @Serializable
    private data class TokenAnswer(
        val token: String = "",
        val email: String = "",
    )

    @Serializable
    private data class LinkAnswer(
        val link: String,
    )

    /** What the backup holds: the sync copy, and when and as what this app last changed it. */
    @Serializable
    private data class Stored(
        val fingerprint: String? = null,
        val changedAt: Long = 0,
        val copy: JsonElement,
    )

    private companion object {
        const val TAG = "TunedAccount"
        const val KEY_TOKEN = "token"
        const val KEY_EMAIL = "email"
        const val TABLET_DP = 600
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val HTTP_CONFLICT = 409
        val JSON = "application/json".toMediaType()
    }
}
