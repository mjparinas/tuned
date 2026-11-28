package com.tunedplayer.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.navigation.NavBackStackEntry

/**
 * Every move between screens is the same cross-fade: opening a screen, Back, and Android's back gesture alike (Melvin,
 * 2026-11-28). Navigation Compose fades forward and on a plain Back, but its default for the predictive back gesture
 * (Android 14 and later) shrinks the old screen away, so Back from Settings looked different from opening it from the
 * bottom bar. These are Navigation's own fade, given to all six transitions.
 */
object ScreenFade {
    private const val DURATION_MS = 700

    val enter: EnterTransition = fadeIn(tween(DURATION_MS))
    val exit: ExitTransition = fadeOut(tween(DURATION_MS))

    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { enter }
    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { exit }

    /** The back gesture's own pair; the swipe edge doesn't change a fade. */
    val predictiveEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = { enter }
    val predictiveExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { exit }
}
