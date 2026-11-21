package com.tunedplayer.app.core

import kotlin.math.abs

/**
 * The extra scale the player puts on the video surface for Zoom and Stretch, on top of Media3's resize mode.
 *
 * Media3's Fit, Zoom and Stretch only change the picture when its shape differs from the screen's. Most channels are
 * 16:9 and so is a TV, so on Android TV all three looked the same and the fit button seemed to do nothing (Melvin,
 * 2026-11-21: "On the Android TV app, it seems like the stretch or fit to screen functionality doesn't work"). When the
 * shapes match, the bars are inside the picture itself (a 4:3 show or a widescreen film sent as 16:9), so Zoom
 * enlarges the picture by 4:3, which takes both kinds of bars off, and Stretch widens it by 4:3, which fills the screen
 * with a 4:3 show the way a TV's own Wide setting does. When the shapes differ, Media3 does the work and this is 1.
 */
object FitScale {
    enum class Mode { FIT, ZOOM, STRETCH }

    /** A 4:3 picture inside 16:9, or a 2.39:1 film inside 16:9, both fill the screen at this scale. */
    const val INNER_BARS = 4f / 3f

    /** Media3 leaves the picture alone when its shape is within 1% of the screen's; the same rule here. */
    private const val SAME_SHAPE = 0.01f

    data class Scale(
        val x: Float,
        val y: Float,
    )

    val NONE = Scale(1f, 1f)

    /** [videoAspect] includes the pixel shape (width × pixel ratio ÷ height); 0 or less when not known yet. */
    fun of(
        mode: Mode,
        videoAspect: Float,
        screenAspect: Float,
    ): Scale {
        if (mode == Mode.FIT || videoAspect <= 0f || screenAspect <= 0f) return NONE
        if (abs(videoAspect / screenAspect - 1f) > SAME_SHAPE) return NONE
        return when (mode) {
            Mode.ZOOM -> Scale(INNER_BARS, INNER_BARS)
            Mode.STRETCH -> Scale(INNER_BARS, 1f)
            Mode.FIT -> NONE
        }
    }
}
