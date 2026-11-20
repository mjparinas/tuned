package com.tunedplayer.app.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

/** Whether this is a TV (Android TV or Google TV), from the system's UI mode, which follows the device itself. */
@Composable
fun isTelevision(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * Many TVs crop the edges of the picture (overscan), so on a TV every screen keeps clear of the outer 5%: Google's
 * TV margins of 48 dp at the sides and 27 dp at the top and bottom. The margin is filled with the theme's background,
 * so it looks like part of the screen. A screen that paints its own backdrop (a glow, a gradient) puts it on a layer
 * with [bleedIntoTvMargin], so the backdrop reaches the screen's edges while its content stays inside the margin;
 * otherwise the plain margin shows as a frame around it (Melvin, 2026-11-20: "there is a thick white boarder").
 * Phones and tablets are unchanged. The player is its own activity and stays full screen: a TV's crop only trims the
 * picture's edge, and its controls keep their own margins.
 */
@Composable
fun TvSafeArea(content: @Composable () -> Unit) {
    if (!isTelevision()) {
        content()
        return
    }
    // The Box doesn't clip, so a backdrop that bleeds into the margin is drawn there.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = TV_SIDE_MARGIN, vertical = TV_TOP_MARGIN),
    ) {
        CompositionLocalProvider(LocalTvMargin provides TvMargin(TV_SIDE_MARGIN, TV_TOP_MARGIN)) { content() }
    }
}

/** The overscan margin [TvSafeArea] keeps around the screen: zero off a TV. */
data class TvMargin(
    val side: Dp,
    val vertical: Dp,
)

val LocalTvMargin = staticCompositionLocalOf { TvMargin(0.dp, 0.dp) }

/**
 * Grows a backdrop layer out by [margin] on every side, so it fills the whole screen rather than stopping at the
 * overscan margin. Only for decoration (a background, a glow): what is drawn there may be cropped by the TV. It takes
 * the same room in its parent as before, so nothing else moves. With no margin (a phone or tablet) it does nothing.
 */
fun Modifier.bleedIntoTvMargin(margin: TvMargin): Modifier {
    if (margin.side <= 0.dp && margin.vertical <= 0.dp) return this
    return layout { measurable, constraints ->
        val x = margin.side.roundToPx().coerceAtLeast(0)
        val y = margin.vertical.roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.offset(horizontal = 2 * x, vertical = 2 * y))
        val width = (placeable.width - 2 * x).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeable.height - 2 * y).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { placeable.place(-x, -y) }
    }
}

private val TV_SIDE_MARGIN = 48.dp
private val TV_TOP_MARGIN = 27.dp
