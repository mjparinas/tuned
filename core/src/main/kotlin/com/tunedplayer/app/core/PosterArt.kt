package com.tunedplayer.app.core

/**
 * How a film's or show's picture fills its 2:3 tile. A real poster is cropped to fill it; a channel logo or a wide
 * still (which providers often send as the "poster" of a channel filed under films) is shown whole and no bigger than
 * the tile allows, because cropping a 300 px wide logo to a tall tile blows its middle up two or three times and
 * looks blurry (Melvin, 2026-11-18).
 *
 * A show is different: its card always shows the show's cover, filling the tile (Melvin, 2026-11-20: "TV Shows use
 * an episode thumbnail but it should use the TV show cover"). TMDB's poster comes first, then the provider's cover,
 * and a wide picture (an episode still some providers send as the show's picture) is cropped to fill the tile rather
 * than floating small in the middle of it.
 */
object PosterArt {
    /** Pictures this tall or taller (width ÷ height up to 0.8) are posters; 2:3 is 0.67. */
    private const val POSTER_MAX_RATIO = 0.8f

    /** Logos get a little room around them inside the tile. */
    const val LOGO_FILL = 0.8f

    fun isPoster(
        width: Float,
        height: Float,
    ): Boolean = width > 0f && height > 0f && width / height <= POSTER_MAX_RATIO

    /** Whether a picture is cropped to fill its tile: always for a show's card, otherwise only a real poster. */
    fun fills(
        width: Float,
        height: Float,
        show: Boolean,
    ): Boolean = show || isPoster(width, height)

    /** A show card's picture: TMDB's poster when the device knows one, otherwise the provider's cover. */
    fun showCover(
        tmdbPoster: String?,
        providerCover: String?,
    ): String? = tmdbPoster?.takeIf { it.isNotBlank() } ?: providerCover?.takeIf { it.isNotBlank() }
}
