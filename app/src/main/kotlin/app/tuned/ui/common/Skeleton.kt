package app.tuned.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import app.tuned.diagnostics.Lighter

/** How many grey stand-ins fill a list while its first page loads. */
const val SKELETON_ROWS = 12

private const val PULSE_MS = 800
private const val PULSE_LOW = 0.45f

/**
 * A softly pulsing grey block standing in for a row or poster that hasn't loaded yet, so a page shows its shape at once
 * instead of a blank (Melvin, 2026-11-17: "Skeletons showing up when switching pages").
 */
@Composable
fun Modifier.skeleton(): Modifier {
    // Lighter mode keeps the block still: a pulse redraws every frame, which a weak TV box feels.
    val still by Lighter.on.collectAsState()
    if (still) return background(MaterialTheme.colorScheme.surfaceContainerHigh)
    val alpha by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 1f,
        targetValue = PULSE_LOW,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    return this
        .graphicsLayer { this.alpha = alpha }
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
}

/** A channel row's stand-in, the same height as the real one. */
@Composable
fun ChannelRowSkeleton() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .skeleton(),
    )
}

/** True while a paged list is loading its first page and has nothing to show yet. */
val LazyPagingItems<*>.firstLoad: Boolean get() = itemCount == 0 && loadState.refresh is LoadState.Loading
