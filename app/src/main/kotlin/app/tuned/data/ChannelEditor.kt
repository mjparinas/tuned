package app.tuned.data

import androidx.room.withTransaction
import app.tuned.core.Search
import app.tuned.data.db.ChannelEditEntity
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Renaming a live channel and giving it another logo (Melvin, 2026-11-19). The new name and logo show everywhere at
 * once (they are written over the cached channel, and over each refresh by [app.tuned.data.db.ChannelEdits]); search
 * finds the provider's name too. Premium in the store build (docs/decisions/premium.md).
 */
class ChannelEditor(
    private val db: TunedDatabase,
) {
    private val edits get() = db.channelEdits()

    fun watch(itemId: Long): Flow<ChannelEditEntity?> = edits.watch(itemId)

    /**
     * Sets the channel's [name] and [logo]; blank, or the same as the provider's, means the provider's. Both blank puts the
     * channel back as the provider sends it.
     */
    suspend fun set(
        itemId: Long,
        name: String?,
        logo: String?,
    ) = db.withTransaction {
        val item = db.items().get(itemId) ?: return@withTransaction
        val old = edits.get(itemId)
        val originalName = old?.originalName ?: item.name
        val originalLogo = if (old != null) old.originalLogo else item.logo
        val newName = name?.trim()?.takeIf { it.isNotEmpty() && it != originalName }
        val newLogo = logo?.trim()?.takeIf { it.isNotEmpty() && it != originalLogo }
        if (newName == null && newLogo == null) {
            edits.delete(itemId)
            edits.show(itemId, originalName, originalLogo, Search.normalize(originalName))
        } else {
            val edit = ChannelEditEntity(itemId, newName, newLogo, originalName, originalLogo)
            edits.upsert(edit)
            edits.show(itemId, newName ?: originalName, newLogo ?: originalLogo, edit.searchName)
        }
    }

    /** Every edit, for sync. */
    suspend fun all(): List<ChannelEditEntity> = edits.all()

    /** Sync: another device's edits in place of this one's, so undoing one there undoes it here. */
    suspend fun replaceAll(incoming: List<ChannelEditEntity>) {
        val keep = incoming.map { it.itemId }.toSet()
        edits.all().filter { it.itemId !in keep }.forEach { set(it.itemId, null, null) }
        incoming.forEach { set(it.itemId, it.name, it.logo) }
    }

    /** The provider's own names of renamed channels, by id, so backup copies still match ([TuneRepository]). */
    suspend fun originalNames(): Map<Long, String> = edits.all().associate { it.itemId to it.originalName }
}
