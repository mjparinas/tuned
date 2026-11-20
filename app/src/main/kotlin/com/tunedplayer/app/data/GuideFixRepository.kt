package com.tunedplayer.app.data

import com.tunedplayer.app.core.GuideFixes
import com.tunedplayer.app.data.db.GuideChannelEntity
import com.tunedplayer.app.data.db.GuideFixEntity
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.work.SyncScheduler
import kotlinx.coroutines.flow.Flow

/**
 * Guide fixes ([GuideFixes]): a channel's own guide entry and time offset, and the extra guide links. A fix shows at
 * once from the guide already on the device; a guide entry the device hasn't kept (the guide had no channel in the
 * playlist for it) arrives with a guide refresh, started here.
 */
class GuideFixRepository(
    private val db: TunedDatabase,
    private val sync: SyncRepository,
    private val scheduler: SyncScheduler,
    private val credentials: CredentialStore,
) {
    private val dao get() = db.guideFixes()

    fun fix(itemId: Long): Flow<GuideFixEntity?> = dao.fix(itemId)

    suspend fun searchChannels(query: String): List<GuideChannelEntity> = dao.searchChannels(query.trim())

    suspend fun channelName(id: String): String? = dao.channelName(id)

    /** Gives [item] the guide of [guideId] (null: the playlist's own), moved by [offsetMinutes]. */
    suspend fun save(
        item: ItemEntity,
        guideId: String?,
        offsetMinutes: Int,
    ) {
        if (guideId == null && offsetMinutes == 0) return clear(item.id)
        val refresh =
            sync.whileIdle {
                val playlistId = dao.get(item.id)?.playlistTvgId ?: item.tvgId?.takeUnless(GuideFixes::isFixed)
                dao.save(GuideFixEntity(item.id, guideId, offsetMinutes, playlistId))
                val fixed = GuideFixes.fixedId(item.id)
                dao.setTvgId(item.id, fixed)
                val source = guideId ?: playlistId
                if (source != null && dao.programmeCount(source) > 0) {
                    dao.copyProgrammes(source, fixed, offsetMinutes * GuideFixes.MINUTE)
                    false
                } else {
                    dao.deleteProgrammes(fixed)
                    source != null // a guide entry the device hasn't kept: read the guide again
                }
            }
        if (refresh) scheduler.refreshNow(guideOnly = true)
    }

    /** Back to the playlist's own guide entry and times. */
    suspend fun clear(itemId: Long) {
        sync.whileIdle {
            val fix = dao.get(itemId) ?: return@whileIdle
            dao.delete(itemId)
            dao.setTvgId(itemId, fix.playlistTvgId)
            dao.deleteProgrammes(GuideFixes.fixedId(itemId))
        }
    }

    fun extraGuides(): List<String> = credentials.load()?.extraGuides.orEmpty()

    /** Saves the extra guide links for the playlist in use and reads the guide again with them. */
    fun setExtraGuides(links: List<String>) {
        val login = credentials.load() ?: return
        val updated = login.copy(extraGuides = links.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        if (updated == login) return
        credentials.save(updated)
        credentials.remember(updated)
        scheduler.refreshNow(guideOnly = true)
    }
}
