package app.tuned.data

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import app.tuned.core.ContentKind
import app.tuned.data.db.ItemEntity
import app.tuned.diagnostics.attempt
import app.tuned.player.CatchUpLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recordings of live channels (from the app audit, item 12): the channel being watched, saved from the player's own
 * stream, so recording never opens a second connection. Each is a `.ts` file with a small `.json` beside it in
 * app-specific storage, next to the offline downloads, so no storage permission is needed and Android removes them with
 * the app. Files rather than a table: a recording belongs to the device, not to a playlist, and outlives a refresh or a
 * playlist switch.
 */
class Recordings(
    context: Context,
) {
    private val storage = context.getSystemService(StorageManager::class.java)

    @Serializable
    data class Recording(
        /** When it started (epoch ms), which also names its files. */
        val id: Long,
        val channelId: Long,
        val channel: String,
        /** What the guide said was on when it started, or null. */
        val title: String? = null,
        val logo: String? = null,
        val durationMs: Long = 0,
        val bytes: Long = 0,
    )

    private val dir: File = (context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR)).also { it.mkdirs() }
    private val changes = MutableStateFlow(0)
    private val recovered = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }

    /** Every finished recording, newest first. */
    val all: Flow<List<Recording>> = changes.map { list() }.flowOn(Dispatchers.IO)

    fun list(): List<Recording> {
        if (recovered.compareAndSet(false, true)) recover()
        return dir
            .listFiles { f -> f.name.endsWith(META) }
            .orEmpty()
            .mapNotNull { read(it) }
            .filter { it.durationMs > 0 && file(it).exists() }
            .sortedByDescending { it.id }
    }

    /**
     * A recording the app never finished (it was closed by Android, or crashed) is kept with the length its file
     * reached, so nothing recorded is lost. One still being written to is left alone.
     */
    private fun recover() {
        val stale = System.currentTimeMillis() - STILL_WRITING_MS
        dir
            .listFiles { f -> f.name.endsWith(META) }
            .orEmpty()
            .mapNotNull { read(it) }
            .filter { it.durationMs == 0L }
            .forEach { r ->
                val video = file(r)
                when {
                    video.exists() && video.lastModified() > stale -> Unit
                    video.length() < MIN_BYTES -> delete(r)
                    else -> write(r.copy(durationMs = video.lastModified() - r.id, bytes = video.length()))
                }
            }
    }

    fun get(id: Long): Recording? = read(File(dir, "$id$META"))

    fun file(r: Recording): File = File(dir, "${r.id}$VIDEO")

    /** Room left for recordings, so one can stop before the storage fills up. */
    fun freeBytes(): Long = attempt(TAG, "Couldn't check the free space") { storage.getAllocatableBytes(storage.getUuidForPath(dir)) }
        .getOrElse { dir.freeSpace }

    /** Starts the files for [r]: its details now, its video as it arrives. */
    fun begin(r: Recording): File {
        write(r)
        return file(r)
    }

    /** Saves how long and how big [r] came out; a recording too short to hold anything is deleted. */
    fun finish(r: Recording) {
        if (r.bytes < MIN_BYTES) delete(r) else write(r)
        changes.update { it + 1 }
    }

    fun delete(r: Recording) {
        file(r).delete()
        File(dir, "${r.id}$META").delete()
        changes.update { it + 1 }
    }

    /** How many recordings there are and the space they take. */
    fun usage(): Pair<Int, Long> = list().size to dir.listFiles().orEmpty().sumOf { it.length() }

    /** Signing out with "Delete": every recording goes. */
    fun deleteAll() {
        dir.listFiles().orEmpty().forEach { it.deleteRecursively() }
        changes.update { it + 1 }
    }

    /** What the player opens for [r]: the file on the device, standing in for a film (seekable, nothing remembered). */
    fun item(r: Recording): ItemEntity = ItemEntity(
        id = CatchUpLinks.ID, // on its own: no resume, no next or previous
        kind = ContentKind.MOVIE.code,
        name = r.title ?: r.channel,
        searchName = "",
        groupName = r.channel,
        region = null,
        adult = false,
        logo = r.logo,
        url = Uri.fromFile(file(r)).toString(),
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = 0,
    )

    private fun write(r: Recording) {
        File(dir, "${r.id}$META").writeText(json.encodeToString(Recording.serializer(), r))
    }

    private fun read(f: File): Recording? {
        if (!f.exists()) return null
        return attempt(TAG, "Couldn't read a recording's details") {
            json.decodeFromString(Recording.serializer(), f.readText())
        }.getOrNull()
    }

    private companion object {
        const val TAG = "Recordings"
        const val DIR = "recordings"
        const val META = ".json"
        const val VIDEO = ".ts"
        const val MIN_BYTES = 64 * 1024L
        const val STILL_WRITING_MS = 60_000L
    }
}
