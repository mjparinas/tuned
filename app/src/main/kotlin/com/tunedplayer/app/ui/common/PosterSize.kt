package com.tunedplayer.app.ui.common

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import com.tunedplayer.app.core.Tmdb

/**
 * Asks TMDB for a poster at the size it's drawn ([Tmdb.posterFor]): a grid tile gets the 342-pixel poster, about half
 * the bytes of the 500-pixel one, while the details screen still gets the full one. Coil knows the tile's size by the
 * time this runs, and the smaller picture is cached under its own address.
 */
object PosterSize : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        val width = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
        val sized = Tmdb.posterFor(url, width)
        if (sized == url) return chain.proceed()
        return chain
            .withRequest(
                chain.request
                    .newBuilder()
                    .data(sized)
                    .build(),
            ).proceed()
    }
}
