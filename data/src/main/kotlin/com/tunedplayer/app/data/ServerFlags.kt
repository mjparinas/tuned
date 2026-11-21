package com.tunedplayer.app.data

import android.content.Context
import com.tunedplayer.app.core.RemoteFlags
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The admin's Flags tab (docs/decisions/admin.md): switches read from `GET /api/flags` a few seconds after the app
 * opens, at most every [MIN_GAP_MS], with the last answer kept on the device. Until one arrives every flag is at its
 * default ([RemoteFlags]), so a device that never reaches the server works as before. Features read [now] at the
 * moment they'd call the service, so a switch takes effect without a restart.
 */
object ServerFlags {
    private const val TAG = "Flags"
    private const val FILE = "flags.json"
    private const val MIN_GAP_MS = 30 * 60_000L
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(RemoteFlags())
    private var savedRead = false
    private var fetchedAt = 0L

    val now: StateFlow<RemoteFlags> = state

    /**
     * The saved answer (the first time), then a fresh one from [base] unless one came in the last half hour.
     * [install] goes along only while usage stats are on, for flags rolled out to a share of installs. Never throws.
     */
    suspend fun refresh(
        context: Context,
        http: OkHttpClient,
        base: String,
        install: String?,
    ): Unit = withContext(Dispatchers.IO) {
        loadSaved(context)
        val file = context.noBackupFilesDir.resolve(FILE)
        val site = base.trim().trimEnd('/')
        val started = System.currentTimeMillis()
        if (site.isEmpty() || started - fetchedAt < MIN_GAP_MS) return@withContext
        fetchedAt = started
        attempt(TAG, "Couldn't fetch the flags") {
            val query = install?.let { "?install=$it" }.orEmpty()
            http.newCall(Request.Builder().url("$site/api/flags$query").build()).execute().use { response ->
                if (response.isSuccessful) {
                    val text = response.body.string()
                    state.value = parse(text)
                    file.writeText(text)
                }
            }
        }
    }

    /** The last answer kept on the device, once per run; [refresh] does it too. */
    suspend fun loadSaved(context: Context): Unit = withContext(Dispatchers.IO) {
        if (savedRead) return@withContext
        savedRead = true
        val file = context.noBackupFilesDir.resolve(FILE)
        attempt(TAG, "Couldn't read the saved flags") { if (file.exists()) state.value = parse(file.readText()) }
    }

    /** Signing out: back to the defaults until the next answer. */
    fun forget(context: Context) {
        context.noBackupFilesDir.resolve(FILE).delete()
        state.value = RemoteFlags()
        fetchedAt = 0L
    }

    /** The server's `{ status, flags: { key: value } }`; anything unreadable is the defaults. */
    internal fun parse(text: String): RemoteFlags {
        val flags =
            attempt(TAG, "Couldn't read the flags") { json.parseToJsonElement(text).jsonObject["flags"]?.jsonObject }.getOrNull()
                ?: return RemoteFlags()
        return RemoteFlags.from(flags.mapValues { (_, v) -> (v as? JsonPrimitive)?.let { p -> p.booleanOrNull ?: p.doubleOrNull } })
    }
}
