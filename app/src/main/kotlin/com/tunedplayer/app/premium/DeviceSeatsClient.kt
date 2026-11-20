package com.tunedplayer.app.premium

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.core.SeatAnswer
import com.tunedplayer.app.core.SeatDevice
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/** What the store build knows about this account's Premium purchase, for the device list. */
data class SeatPurchase(
    val productId: String,
    val token: String,
)

/**
 * The Premium device list (docs/decisions/premium.md): at most three devices per purchase. It sends only the Play
 * purchase token (which the list checks with Play and keeps only hashed), a hash of this device's id, and the
 * device's name. Every failure is [SeatAnswer.Unreachable], which never locks anyone out.
 */
class DeviceSeatsClient(
    private val http: OkHttpClient,
    private val base: String,
    private val device: String,
    private val name: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Takes a seat for this device, or says the list is full. */
    suspend fun activate(purchase: SeatPurchase): SeatAnswer = post("activate", Ask(purchase.productId, purchase.token, device, name))

    /** Frees [target]'s seat (this device's own, or another's) and answers for this device. */
    suspend fun deactivate(
        purchase: SeatPurchase,
        target: String,
    ): SeatAnswer = post("deactivate", Ask(purchase.productId, purchase.token, device, name, target))

    private suspend fun post(
        path: String,
        ask: Ask,
    ): SeatAnswer = withContext(Dispatchers.IO) {
        attempt(TAG, "Couldn't reach the device list") {
            val body = json.encodeToString(ask).toRequestBody(JSON)
            val request =
                Request
                    .Builder()
                    .url("$base/api/seats/$path")
                    .post(body)
                    .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use SeatAnswer.Unreachable
                answer(json.decodeFromString<Reply>(response.body.string()))
            }
        }.getOrDefault(SeatAnswer.Unreachable)
    }

    private fun answer(reply: Reply): SeatAnswer {
        val devices = reply.devices.map { SeatDevice(it.id, it.name, it.lastSeen, it.id == device) }
        return when (reply.status) {
            "active" -> SeatAnswer.Active(devices)
            "full" -> SeatAnswer.Full(devices)
            else -> SeatAnswer.Unreachable
        }
    }

    @Serializable
    private data class Ask(
        val product: String,
        val token: String,
        val device: String,
        val name: String,
        val target: String? = null,
    )

    @Serializable
    private data class Reply(
        val status: String,
        val devices: List<Device> = emptyList(),
    )

    @Serializable
    private data class Device(
        val id: String,
        val name: String,
        val lastSeen: Long = 0,
    )

    companion object {
        private const val TAG = "Premium"
        private val JSON = "application/json".toMediaType()

        /** Null when this build has no device list (the `github` build, or a store build without the address). */
        fun create(
            context: Context,
            http: OkHttpClient,
        ): DeviceSeatsClient? {
            val base = BuildConfig.DEVICES_URL.trim().trimEnd('/')
            if (Premium.ALWAYS || base.isEmpty()) return null
            return DeviceSeatsClient(http, base, deviceId(context), deviceName(context))
        }

        /**
         * Android's id for this app on this device, hashed. It stays the same through an uninstall and reinstall,
         * so reinstalling never uses up a seat.
         */
        @SuppressLint("HardwareIds") // the point: a reinstall on the same device must reuse its seat
        internal fun deviceId(context: Context): String {
            val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            val digest = MessageDigest.getInstance("SHA-256").digest("tuned-seat:$id".toByteArray())
            return digest.joinToString("") { "%02x".format(it) }.take(ID_LENGTH)
        }

        /** The name the person gave the device ("Living room TV"), else its maker and model. */
        internal fun deviceName(context: Context): String {
            val given = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            val maker = Build.MANUFACTURER.replaceFirstChar(Char::uppercase)
            val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
            return (given?.takeIf { it.isNotBlank() } ?: model).take(NAME_LENGTH)
        }

        private const val ID_LENGTH = 32
        private const val NAME_LENGTH = 60
    }
}
