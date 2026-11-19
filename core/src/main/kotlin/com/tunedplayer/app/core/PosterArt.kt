package com.tunedplayer.app.core

/**
 * How a film's or show's picture fills its 2:3 tile. A real poster is cropped to fill it; a channel logo or a wide
 * still (which providers often send as the "poster" of a channel filed under films) is shown whole and no bigger than
 * the tile allows, because cropping a 300 px wide logo to a tall tile blows its middle up two or three times and
 * looks blurry (Melvin, 2026-11-18).
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
}
