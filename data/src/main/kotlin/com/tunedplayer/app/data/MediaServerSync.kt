package com.tunedplayer.app.data

import com.tunedplayer.app.core.Episodes
import com.tunedplayer.app.core.MediaServer
import com.tunedplayer.app.core.PlaylistEntry
import com.tunedplayer.app.core.Programme
import com.tunedplayer.app.data.db.ItemEntity
import com.tunedplayer.app.data.db.ShowCoverEntity
import com.tunedplayer.app.data.db.ShowGroups
import com.tunedplayer.app.data.db.TunedDatabase
import com.tunedplayer.app.diagnostics.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** What [MediaServerSync] needs from [SyncRepository] without growing either constructor past detekt's limits. */
internal interface MediaServerSyncHost {
    fun forgetMemo()

    suspend fun importPlaylist(
        progress: (SyncProgress) -> Unit,
        scope: String?,
        read: SyncProgress.Step,
        save: SyncProgress.Step,
        block: suspend (suspend (PlaylistEntry) -> Unit) -> Unit,
    ): Int

    suspend fun guideFile(
        url: String,
        progress: (SyncProgress) -> Unit,
        read: suspend (InputStream) -> Unit,
    )

    fun toRow(entry: PlaylistEntry): ItemEntity

    fun toEntry(item: ItemEntity): PlaylistEntry
}

/**
 * Plex, Jellyfin and Emby refresh steps, kept beside [SyncRepository] so that class stays under detekt's size and
 * function caps (Melvin, 2026-11-22). Live channels first, then films and shows, then the server's own guide.
 */
internal class MediaServerSync(
    private val db: TunedDatabase,
    private val media: MediaServerLinks,
    private val currentLogin: () -> Credentials?,
    private val settings: SettingsRepository,
    private val reads: ReadProgress,
    private val host: MediaServerSyncHost,
) {
    /** Live TV channels only; films and shows follow in [refreshLibrary]. */
    suspend fun importLive(
        login: Credentials,
        progress: (SyncProgress) -> Unit,
    ): Int {
        host.forgetMemo()
        progress(SyncProgress(SyncProgress.Step.DownloadPlaylist))
        val product = MediaServer.Product.ofMarker(login.playlistUrl) ?: return 0
        val channels = withContext(Dispatchers.IO) { liveChannels(login, product) }
        return host.importPlaylist(progress, "kind = 0", SyncProgress.Step.ReadPlaylist, SyncProgress.Step.SavePlaylist) { add ->
            channels.forEachIndexed { order, channel -> add(MediaServer.live(product, channel, order)) }
        }
    }

    /**
     * Films and shows after Live TV and the guide. Shows are one stand-in each until opened ([loadShow]); an opened
     * show keeps its episodes on the next refresh.
     */
    suspend fun refreshLibrary(progress: (SyncProgress) -> Unit) {
        val login = currentLogin()?.takeIf { it.kind.isMediaServer } ?: return
        val product = MediaServer.Product.ofMarker(login.playlistUrl) ?: return
        val fetched = db.shows().fetchedEpisodes().groupBy { it.showKey }
        var order = db.items().count()
        val library = SyncProgress.Step.Library
        progress(SyncProgress(library))
        val (films, shows) = withContext(Dispatchers.IO) { catalog(login, product) }
        host.importPlaylist(progress, "kind != 0", library, library) { add ->
            for (item in films) add(MediaServer.movie(product, item, order++))
            for (item in shows) {
                val kept = fetched[Episodes.showKey(item.name)]
                if (kept.isNullOrEmpty()) {
                    add(MediaServer.showStandIn(product, item, order++))
                } else {
                    kept.forEach { add(host.toEntry(it)) }
                }
            }
        }
    }

    /** The server's Live TV guide, filed under each channel's guide id. */
    suspend fun refreshGuide(
        login: Credentials,
        progress: (SyncProgress) -> Unit,
    ): Int {
        val product = MediaServer.Product.ofMarker(login.playlistUrl) ?: return 0
        val s = settings.settings.first()
        val import = GuideImport(db, s.guideDays, s.guideOffsetMinutes)
        val saved = { rows: Int -> progress(reads.at(SyncProgress.Step.ReadGuide, rows)) }
        val guideIds =
            db
                .items()
                .liveLinks()
                .mapNotNull { link -> MediaServer.parse(link.url)?.let { it.id to link.tvgId } }
                .toMap()
        val hours = (s.guideDays * HOURS_A_DAY).coerceIn(HOURS_A_DAY, MAX_GUIDE_HOURS)
        val count =
            try {
                if (!import.begin()) return 0
                progress(SyncProgress(SyncProgress.Step.DownloadGuide))
                import.read(programmes(login, product, hours, guideIds), saved)
                for (extra in login.extraGuides) {
                    attempt(TAG, "An extra guide failed") { host.guideFile(extra, progress) { input -> import.read(input, saved) } }
                }
                progress(SyncProgress(SyncProgress.Step.SaveGuide))
                import.commit()
            } finally {
                import.discard()
            }
        settings.update { it.copy(lastGuideSync = System.currentTimeMillis()) }
        return count
    }

    /** Episodes for a stand-in show, fetched when it is first opened. */
    suspend fun loadShow(
        showKey: String,
        standIn: ItemEntity,
    ) {
        val show = MediaServer.standIn(standIn.url) ?: return
        val login = currentLogin()?.takeIf { it.kind.isMediaServer } ?: return
        val name = standIn.showName ?: standIn.name
        var order = standIn.sortOrder
        val episodes =
            withContext(Dispatchers.IO) { episodes(login, show) }
                .map { ep -> MediaServer.episode(show.product, name, standIn.groupName, standIn.logo, ep, order++) }
        if (episodes.isEmpty()) return
        standIn.logo?.takeIf { it.isNotBlank() }?.let { db.shows().saveCovers(listOf(ShowCoverEntity(showKey, it))) }
        db.shows().replaceStandIn(standIn.id, episodes.map { host.toRow(it) })
        ShowGroups.rebuild(db)
    }

    private fun liveChannels(
        login: Credentials,
        product: MediaServer.Product,
    ): List<MediaServer.Channel> = when (product) {
        MediaServer.Product.Plex -> {
            media.plex(login).also { it.signIn() }.channels()
        }

        MediaServer.Product.Jellyfin, MediaServer.Product.Emby -> {
            media.emby(login).also { if (!it.signIn()) throw IOException("Login refused") }.channels()
        }
    }

    private fun catalog(
        login: Credentials,
        product: MediaServer.Product,
    ): Pair<List<MediaServer.Item>, List<MediaServer.Item>> = when (product) {
        MediaServer.Product.Plex -> {
            val server = media.plex(login)
            server.movies() to server.shows()
        }

        MediaServer.Product.Jellyfin, MediaServer.Product.Emby -> {
            val server = media.emby(login)
            server.movies() to server.shows()
        }
    }

    private fun episodes(
        login: Credentials,
        show: MediaServer.StandIn,
    ): List<MediaServer.Episode> = when (show.product) {
        MediaServer.Product.Plex -> media.plex(login).episodes(show.id)
        MediaServer.Product.Jellyfin, MediaServer.Product.Emby -> media.emby(login).episodes(show.id)
    }

    private suspend fun programmes(
        login: Credentials,
        product: MediaServer.Product,
        hours: Int,
        guideIds: Map<String, String?>,
    ): Flow<Programme> {
        val list =
            withContext(Dispatchers.IO) {
                when (product) {
                    MediaServer.Product.Plex -> media.plex(login).guide(hours)
                    MediaServer.Product.Jellyfin, MediaServer.Product.Emby -> media.emby(login).guide(hours)
                }
            }
        return list.asFlow().map { p -> guideIds[p.channelId]?.let { p.copy(channelId = it) } ?: p }
    }

    private companion object {
        const val TAG = "Sync"
        const val HOURS_A_DAY = 24
        const val MAX_GUIDE_HOURS = 24 * 7
    }
}
