package com.tunedplayer.app.data

import android.content.Context
import com.tunedplayer.app.diagnostics.attempt
import java.io.File

/**
 * The sync copy as this device and the backup last agreed on it, which [CopyMerge] joins both sides' changes against
 * ([CloudSyncRepository]). It holds the playlists' logins, so it is sealed with a Keystore key as the login is, and
 * signing out deletes it ([CloudSyncRepository.disconnect]).
 */
open class LastSynced(
    context: Context,
) {
    private val file = File(context.noBackupFilesDir, "backup-base")
    private val box by lazy { KeystoreBox(ALIAS) }

    /** Null before the first sync, or when it can't be read: the join then counts both sides' parts as added. */
    open fun load(): String? = if (!file.exists()) null else attempt(TAG, "Couldn't read the last synced copy") { box.open(file.readText()) }.getOrNull()

    open fun save(copy: ByteArray) {
        attempt(TAG, "Couldn't keep the last synced copy") {
            val next = File(file.parentFile, "${file.name}.new")
            next.writeText(box.seal(copy.decodeToString()))
            if (!next.renameTo(file)) {
                file.delete()
                next.renameTo(file)
            }
        }
    }

    open fun clear() {
        attempt(TAG, "Couldn't delete the last synced copy") { file.delete() }
    }

    private companion object {
        const val TAG = "Backup"
        const val ALIAS = "tuned.backup.base"
    }
}
