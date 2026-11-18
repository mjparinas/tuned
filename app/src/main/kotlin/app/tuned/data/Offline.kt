package app.tuned.data

import android.content.Context
import android.os.StatFs
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.tuned.core.StreamLink
import app.tuned.data.db.DownloadEntity
import app.tuned.data.db.ItemEntity
import app.tuned.data.db.TunedDatabase
import app.tuned.diagnostics.attempt
import app.tuned.work.OfflineWorker
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Films and episodes saved to watch without a connection. Downloads go one at a time in the background, and stop
 * while anything plays so the provider never sees two connections at once (Melvin, 2026-11-17); they carry on when
 * the player closes.
 */
class Offline(
    private val context: Context,
    private val db: TunedDatabase,
    private val settings: SettingsRepository,
    /** True while something is watched, on the phone or a TV; nothing downloads then (one connection, ever). */
    private val watching: () -> Boolean,
) {
    private val dir: File get() = context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR)
    private val work get() = WorkManager.getInstance(context)

    val all = db.downloads().all()
    val rows = db.downloads().rows()

    suspend fun add(item: ItemEntity) {
        queue(item, System.currentTimeMillis())
        start()
    }

    /** A whole season, saved in episode order, one at a time like any other download. Ones already saved stay. */
    suspend fun addAll(items: List<ItemEntity>) {
        val now = System.currentTimeMillis()
        items.forEachIndexed { index, item -> if (db.downloads().get(item.id) == null) queue(item, now + index) }
        start()
    }

    private suspend fun queue(
        item: ItemEntity,
        addedAt: Long,
    ) {
        val ext =
            StreamLink
                .address(item.url)
                .substringBefore('?')
                .substringAfterLast('/')
                .substringAfterLast('.', "mp4")
                .take(MAX_EXT)
        val file = File(dir, "${item.id}.$ext")
        db.downloads().upsert(DownloadEntity(item.id, item.name, item.url, file.path, addedAt = addedAt))
    }

    /** Holds one download back; the rest carry on. */
    suspend fun hold(itemId: Long) {
        val running = db.downloads().next()?.itemId == itemId
        db.downloads().state(itemId, DownloadEntity.PAUSED)
        if (running) {
            work.cancelUniqueWork(OfflineWorker.NAME)
            start()
        }
    }

    /** Carry on with a held or failed download. */
    suspend fun retry(itemId: Long) {
        db.downloads().state(itemId, DownloadEntity.QUEUED)
        start()
    }

    suspend fun remove(itemId: Long) {
        val row = db.downloads().get(itemId) ?: return
        // A download in progress stops; the next one starts once this row is gone.
        work.cancelUniqueWork(OfflineWorker.NAME)
        File(row.path).delete()
        File(row.path + ".part").delete()
        File(row.path + ".part.tag").delete()
        db.downloads().delete(itemId)
        start()
    }

    /** The saved files among [ids], to play instead of streaming. */
    suspend fun files(ids: List<Long>): Map<Long, File> = db
        .downloads()
        .done(ids)
        .map { it.itemId to File(it.path) }
        .filter { it.second.exists() }
        .toMap()

    /** Space left where downloads are saved, or null when Android won't say. */
    fun freeBytes(): Long? =
        attempt("Offline", "Couldn't read free space") { StatFs(dir.apply { mkdirs() }.path).availableBytes }.getOrNull()

    /** The player opened: downloads wait. */
    fun pause() {
        work.cancelUniqueWork(OfflineWorker.NAME)
    }

    /** The player closed, or something was added: carry on with what's left. */
    suspend fun start() {
        // Something added while a stream plays (in picture-in-picture, say) waits: the player starts it on closing.
        if (watching() || db.downloads().next() == null) return
        val unmetered = settings.settings.first().unmeteredOnly
        val request =
            OneTimeWorkRequestBuilder<OfflineWorker>()
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .build(),
                ).build()
        work.enqueueUniqueWork(OfflineWorker.NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val DIR = "offline"
        const val MAX_EXT = 5
    }
}
