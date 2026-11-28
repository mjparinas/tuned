package com.tunedplayer.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Back, and the back gesture, use the same cross-fade as opening a screen, never Navigation's shrinking back gesture. */
class ScreenFadeTest {
    @Test
    fun everyMoveIsNavigationsOwnFade() {
        assertEquals(fadeIn(tween(700)), ScreenFade.enter)
        assertEquals(fadeOut(tween(700)), ScreenFade.exit)
    }

    @Test
    fun backNeverShrinksTheScreen() {
        // Navigation Compose's default for the predictive back gesture, which Melvin saw on Back from Settings.
        assertNotEquals(scaleOut(targetScale = 0.7f), ScreenFade.exit)
    }
}
