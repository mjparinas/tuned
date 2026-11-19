package app.tuned.data

import app.tuned.core.TvHandoff
import app.tuned.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The relay for signing in on a TV with a phone (`devices/lib/tv.mjs`, docs/decisions/onboarding.md). It only ever
 * carries the TV's public key and boxes sealed for it ([TvHandoff]); a failure is an answer, never an exception.
 */
class TvRelay(
    private val http: OkHttpClient,
    val base: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A code for this TV's key, and the secret only this TV polls with. */
    data class Code(
        val code: String,
        val secret: String,
        val seconds: Int,
    )

    sealed interface Taken {
        data object Waiting : Taken

        /** The code ran out (or was already used): make a new one. */
        data object Gone : Taken

        data object Unreachable : Taken

        class Box(
            val box: ByteArray,
        ) : Taken

        /** The website signed this device in to a Tuned account: a one-time code for [TunedAccount.signIn]. */
        class Linked(
            val link: String,
        ) : Taken
    }

    enum class Sent { Ok, Gone, AlreadySent, TooMany, Unreachable }

    suspend fun code(publicKey: ByteArray): Code? = post("code", Ask(key = TvHandoff.b64(publicKey)))?.let { reply ->
        if (reply.status == "ok" && reply.code != null && reply.secret != null) Code(reply.code, reply.secret, reply.seconds) else null
    }

    suspend fun take(code: Code): Taken {
        val reply = post("take", Ask(code = code.code, secret = code.secret)) ?: return Taken.Unreachable
        return when {
            reply.status == "ok" && reply.link != null -> Taken.Linked(reply.link)
            reply.status == "ok" && reply.box != null -> runCatching { Taken.Box(TvHandoff.unb64(reply.box)) }.getOrDefault(Taken.Gone)
            reply.status == "waiting" -> Taken.Waiting
            reply.status == "not found" -> Taken.Gone
            else -> Taken.Unreachable
        }
    }

    /** The TV's key for a typed code, to check with [TvHandoff.checkDigits]; null when there's no such code. */
    suspend fun key(code: String): Result<ByteArray?> {
        val reply = post("key", Ask(code = code)) ?: return Result.failure(IllegalStateException("unreachable"))
        val key = reply.key?.let { runCatching { TvHandoff.unb64(it) }.getOrNull() }?.takeIf(TvHandoff::validKey)
        return when {
            reply.status == "ok" && key != null -> Result.success(key)
            reply.status == "not found" || reply.status == "bad code" -> Result.success(null)
            else -> Result.failure(IllegalStateException(reply.status))
        }
    }

    suspend fun send(
        code: String,
        box: ByteArray,
    ): Sent {
        val reply = post("send", Ask(code = code, box = TvHandoff.b64(box))) ?: return Sent.Unreachable
        return when (reply.status) {
            "ok" -> Sent.Ok
            "not found" -> Sent.Gone
            "already sent" -> Sent.AlreadySent
            "too many" -> Sent.TooMany
            else -> Sent.Unreachable
        }
    }

    /** Null when the relay can't be reached at all; otherwise its answer, error statuses included. */
    private suspend fun post(
        path: String,
        ask: Ask,
    ): Reply? = withContext(Dispatchers.IO) {
        attempt(TAG, "Couldn't reach the TV sign-in relay") {
            val request =
                Request
                    .Builder()
                    .url("${base.trimEnd('/')}/api/tv/$path")
                    .post(json.encodeToString(ask).toRequestBody(JSON))
                    .build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                runCatching { json.decodeFromString<Reply>(text) }.getOrNull()
            }
        }.getOrNull()
    }

    @Serializable
    private data class Ask(
        val key: String? = null,
        val code: String? = null,
        val secret: String? = null,
        val box: String? = null,
    )

    @Serializable
    private data class Reply(
        val status: String = "",
        val code: String? = null,
        val secret: String? = null,
        val seconds: Int = TvHandoff.CODE_SECONDS,
        val key: String? = null,
        val box: String? = null,
        val link: String? = null,
    )

    private companion object {
        const val TAG = "TvSignIn"
        val JSON = "application/json".toMediaType()
    }
}
