package com.tunedplayer.app.work

import com.tunedplayer.app.data.Metadata
import com.tunedplayer.app.data.MetadataRepository
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.diagnostics.launchSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Gets a film's or show's page ready before it is opened (Melvin, 2026-11-28: "prefetch on focus", "should happen for
 * movies as well"): its TMDB details (summary, genres, running time, cast) and its backdrop, poster and first cast
 * photos in the image cache, so the page draws whole the moment OK or a tap opens it. On a TV it starts once the
 * remote rests on a poster ([REST_MS]); on a phone, the moment a finger comes down on one. TMDB and pictures only,
 * never the provider, so it opens no stream connection, and one title at a time: moving on cancels the last.
 */
class TitlePrefetch(
    private val scope: CoroutineScope,
    private val metadata: () -> MetadataRepository,
    private val warm: () -> BrowseWarm,
    /** Settings › Appearance › Get the next screens ready, and a network to ask. */
    private val allowed: suspend () -> Boolean,
) {
    private var job: Job? = null
    private var current: String? = null

    /** A finger came down on a poster: start now, without waiting, since the tap usually follows. */
    fun pressed(
        name: String,
        tv: Boolean,
    ) {
        val key = key(name, tv)
        if (key == current && job?.isActive == true) return
        job?.cancel()
        current = key
        job = scope.launchSafely(TAG) { details(name, tv) }
    }

    /**
     * The details for [name], fetched and cached if the device doesn't have them yet, with its pictures queued into
     * the image cache. What the device already had when the network can't be asked, or null.
     */
    suspend fun details(
        name: String,
        tv: Boolean,
    ): Metadata? {
        val repo = metadata()
        if (!repo.hasKey() || !allowed()) return attempt(TAG, "cached details") { repo.cached(name, tv) }.getOrNull()
        val meta = attempt(TAG, "Couldn't prefetch details for a title") { repo.details(name, tv) }.getOrNull() ?: return null
        warm().enqueueArt(art(meta))
        return meta
    }

    companion object {
        private const val TAG = "TitlePrefetch"

        /** How long the remote rests on a poster before its page is fetched, so flipping along a row asks nothing. */
        const val REST_MS = 350L

        /** The cast photos a details page shows first. */
        private const val CAST_PHOTOS = 6

        fun key(
            name: String,
            tv: Boolean,
        ) = "$tv:$name"

        /** The pictures a details page draws first: backdrop, poster, then the first cast photos. */
        fun art(meta: Metadata): List<String> = listOfNotNull(meta.backdrop, meta.poster) +
            meta.cast.take(CAST_PHOTOS).mapNotNull { it.photo }
    }
}
