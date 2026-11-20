package com.tunedplayer.app.data

import android.content.Context
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.data.db.TunedDatabase

/**
 * Before a film or episode downloads, its page is saved too: the TMDB details (summary, rating, cast) and every
 * picture on it (poster, backdrop, cast photos, the playlist's own artwork), so the page looks the same offline
 * (Melvin, 2026-11-17: "cache as much as we can"). Pictures go to the image loader's disk cache only; nothing is
 * decoded at full size or held in memory.
 */
class SavedArtwork(
    private val context: Context,
    private val db: TunedDatabase,
    private val metadata: MetadataRepository,
) {
    suspend fun keep(itemId: Long) {
        val item = db.items().get(itemId) ?: return
        val show = ContentKind.fromCode(item.kind) == ContentKind.SERIES
        val title = if (show) item.showName ?: item.name else item.name
        val details = metadata.details(title, tv = show)
        val pictures =
            buildList {
                add(item.logo)
                add(details?.poster)
                add(details?.backdrop)
                details?.cast?.forEach { add(it.photo) }
            }.filterNotNull().filter { it.isNotBlank() }.distinct()
        val loader = SingletonImageLoader.get(context)
        pictures.forEach { url ->
            loader.execute(
                ImageRequest
                    .Builder(context)
                    .data(url)
                    .size(Size(THUMB_PX, THUMB_PX))
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build(),
            )
        }
    }

    private companion object {
        /** The disk cache keeps the original; decoding a thumbnail only proves it arrived. */
        const val THUMB_PX = 64
    }
}
