package app.tuned.ui.common

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** Whether this is a TV (Android TV or Google TV), from the system's UI mode, which follows the device itself. */
@Composable
fun isTelevision(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * Many TVs crop the edges of the picture (overscan), so on a TV every screen keeps clear of the outer 5%: Google's
 * TV margins of 48 dp at the sides and 27 dp at the top and bottom. The margin is filled with the theme's background,
 * so it looks like part of the screen. Phones and tablets are unchanged. The player is its own activity and stays
 * full screen: a TV's crop only trims the picture's edge, and its controls keep their own margins.
 */
@Composable
fun TvSafeArea(content: @Composable () -> Unit) {
    if (!isTelevision()) {
        content()
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = TV_SIDE_MARGIN, vertical = TV_TOP_MARGIN),
    ) {
        content()
    }
}

private val TV_SIDE_MARGIN = 48.dp
private val TV_TOP_MARGIN = 27.dp
