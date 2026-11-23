package com.tunedplayer.app.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import com.tunedplayer.app.data.AppSettings

/** Whether this is a TV (Android TV or Google TV), from the system's UI mode, which follows the device itself. */
@Composable
fun isTelevision(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * Many TVs crop the edges of the picture (overscan), so on a TV every screen keeps a margin: by default 12 dp at the
 * sides and 16 dp at the top and bottom (tighter than Google's 48 / 27; Melvin, 2026-11-22: still too much padding),
 * filled with the theme's background. Settings › Appearance › Calibrate screen shifts and scales that margin per TV
 * ([LocalTvCalibration]), and the choice is backed up with the Tuned account. A screen that paints its own backdrop
 * (a glow, a gradient) puts it on a layer with [bleedIntoTvMargin], so the backdrop reaches the screen's edges while
 * its content stays inside the margin; otherwise the plain margin shows as a frame around it (Melvin, 2026-11-20:
 * "there is a thick white boarder"). Phones and tablets are unchanged. The player is its own activity and stays full
 * screen: a TV's crop only trims the picture's edge, and its controls keep their own margins.
 */
@Composable
fun TvSafeArea(content: @Composable () -> Unit) {
    if (!isTelevision()) {
        content()
        return
    }
    val margin = LocalTvCalibration.current.toMargin()
    // The Box doesn't clip, so a backdrop that bleeds into the margin is drawn there.
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(PaddingValues(start = margin.start, top = margin.top, end = margin.end, bottom = margin.bottom)),
    ) {
        CompositionLocalProvider(LocalTvMargin provides margin) { content() }
    }
}

/**
 * How far and how large the TV safe area is, from Settings › Appearance › Calibrate screen. [offsetX] and [offsetY]
 * are in dp (positive right / down); [zoom] of 1 is the default margins, above 1 stretches toward the edges, below 1
 * shrinks. Provided above [TvSafeArea] so dialogs that open their own safe area (a [TunedSheet] on a TV) share it.
 */
data class TvCalibration(
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val zoom: Float = 1f,
) {
    /** The padding [TvSafeArea] keeps; never negative, so a large offset only eats the opposite side. */
    fun toMargin(): TvMargin {
        val z = zoom.coerceIn(TV_ZOOM_MIN, TV_ZOOM_MAX)
        val side = TV_SIDE_MARGIN / z
        val vertical = TV_VERTICAL_MARGIN / z
        val ox = offsetX.coerceIn(-TV_OFFSET_MAX, TV_OFFSET_MAX).dp
        val oy = offsetY.coerceIn(-TV_OFFSET_MAX, TV_OFFSET_MAX).dp
        return TvMargin(
            start = (side - ox).coerceAtLeast(0.dp),
            top = (vertical - oy).coerceAtLeast(0.dp),
            end = (side + ox).coerceAtLeast(0.dp),
            bottom = (vertical + oy).coerceAtLeast(0.dp),
        )
    }
}

fun AppSettings.tvCalibration() = TvCalibration(tvOffsetX, tvOffsetY, tvZoom)

/** The overscan margin [TvSafeArea] keeps around the screen: zero off a TV. */
data class TvMargin(
    val start: Dp,
    val top: Dp,
    val end: Dp,
    val bottom: Dp,
) {
    constructor(side: Dp, vertical: Dp) : this(side, vertical, side, vertical)
}

val LocalTvMargin = staticCompositionLocalOf { TvMargin(0.dp, 0.dp) }

/** The calibration in use; default until Settings have loaded. */
val LocalTvCalibration = staticCompositionLocalOf { TvCalibration() }

/**
 * Grows a backdrop layer out by [margin] on every side, so it fills the whole screen rather than stopping at the
 * overscan margin. Only for decoration (a background, a glow): what is drawn there may be cropped by the TV. It takes
 * the same room in its parent as before, so nothing else moves. With no margin (a phone or tablet) it does nothing.
 */
fun Modifier.bleedIntoTvMargin(margin: TvMargin): Modifier {
    if (margin.start <= 0.dp && margin.top <= 0.dp && margin.end <= 0.dp && margin.bottom <= 0.dp) return this
    return layout { measurable, constraints ->
        val left = margin.start.roundToPx().coerceAtLeast(0)
        val top = margin.top.roundToPx().coerceAtLeast(0)
        val right = margin.end.roundToPx().coerceAtLeast(0)
        val bottom = margin.bottom.roundToPx().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.offset(horizontal = left + right, vertical = top + bottom))
        val width = (placeable.width - left - right).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeable.height - top - bottom).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) { placeable.place(-left, -top) }
    }
}

/** Default side margin on a TV before calibration; tests and the calibrate screen use the same number. */
internal val TV_SIDE_MARGIN = 12.dp

/** Default top and bottom margin on a TV before calibration. */
internal val TV_VERTICAL_MARGIN = 16.dp

/** How far left/right or up/down calibration can shift, in dp. */
internal const val TV_OFFSET_MAX = 40

/** Smallest and largest size against the default margins (0.7 = more padding, 1.4 = less). */
internal const val TV_ZOOM_MIN = 0.7f
internal const val TV_ZOOM_MAX = 1.4f
