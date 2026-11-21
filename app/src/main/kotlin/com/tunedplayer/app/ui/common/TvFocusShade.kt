package com.tunedplayer.app.ui.common

import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * On a TV, a stronger shade on whatever the remote is on (Melvin, 2026-11-21: controls with a remote "should be very
 * intuitive"). Material's focus shade is a tenth of the content colour, made for a keyboard at a desk and hard to see
 * from the sofa, so every button, chip and row without its own outline ([focusRing]) gets a quarter instead. Phones and
 * tablets keep Material's, since touch never focuses anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvFocusShade(content: @Composable () -> Unit) {
    if (!isTelevision()) {
        content()
        return
    }
    CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(rippleAlpha = TvRippleAlpha), content = content)
}

private val TvRippleAlpha =
    RippleAlpha(
        draggedAlpha = 0.16f,
        focusedAlpha = 0.24f,
        hoveredAlpha = 0.08f,
        pressedAlpha = 0.16f,
    )
