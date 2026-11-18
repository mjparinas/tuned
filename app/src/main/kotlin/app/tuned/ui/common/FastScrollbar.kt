package app.tuned.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import app.tuned.core.FastScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the scroll bar needs from a lazy list or grid, so one bar serves both. */
@Stable
class FastScrollTarget internal constructor(
    val first: () -> Int,
    val visible: () -> Int,
    val total: () -> Int,
    val scrolling: () -> Boolean,
    val jump: suspend (Int) -> Unit,
)

@Composable
fun rememberFastScroll(state: LazyListState): FastScrollTarget = remember(state) {
    FastScrollTarget(
        first = { state.firstVisibleItemIndex },
        visible = { state.layoutInfo.visibleItemsInfo.size },
        total = { state.layoutInfo.totalItemsCount },
        scrolling = { state.isScrollInProgress },
        jump = { state.scrollToItem(it) },
    )
}

@Composable
fun rememberFastScroll(state: LazyGridState): FastScrollTarget = remember(state) {
    FastScrollTarget(
        first = { state.firstVisibleItemIndex },
        visible = { state.layoutInfo.visibleItemsInfo.size },
        total = { state.layoutInfo.totalItemsCount },
        scrolling = { state.isScrollInProgress },
        jump = { state.scrollToItem(it) },
    )
}

/**
 * The scroll bar down the right of a long list: shows while the list moves (or always, with Settings › Channels and
 * lists › Always show the scroll bar) and fades a moment after. Drag its thumb to cross thousands of rows in one move,
 * with a bubble naming where it is ([label], given a row's index; null keeps the last one). On a TV it shows while the
 * D-pad scrolls and takes no focus. Lay it over the list in a Box.
 *
 * Speed: the thumb's position and fade are read in layout and draw only, so scrolling never recomposes the bar.
 */
@Composable
fun FastScrollbar(
    target: FastScrollTarget,
    alwaysShow: Boolean,
    modifier: Modifier = Modifier,
    label: (Int) -> String?,
) {
    val needed by remember(target) { derivedStateOf { FastScroll.needed(target.visible(), target.total()) } }
    if (!needed) return
    val scope = rememberCoroutineScope()
    val labelFor by rememberUpdatedState(label)
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var bubble by remember { mutableStateOf<String?>(null) }
    val scrolling by remember(target) { derivedStateOf { target.scrolling() } }
    var shown by remember { mutableStateOf(alwaysShow) }
    LaunchedEffect(scrolling, dragging, alwaysShow) {
        if (scrolling || dragging || alwaysShow) {
            shown = true
        } else {
            delay(HIDE_AFTER_MS)
            shown = false
        }
    }
    val onMove = { delta: Float ->
        dragFraction = (dragFraction + delta).coerceIn(0f, 1f)
        val index = FastScroll.index(dragFraction, target.visible(), target.total())
        if (index != dragIndex) {
            dragIndex = index
            labelFor(index)?.let { bubble = it }
            scope.launch { target.jump(index) }
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "scrollbar")
    val colour = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    BoxWithConstraints(modifier.fillMaxSize().clearAndSetSemantics {}) {
        val density = LocalDensity.current
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val range = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        val fraction = {
            if (dragging) dragFraction else FastScroll.fraction(target.first(), target.visible(), target.total())
        }
        val thumbOffset = { IntOffset(0, (fraction() * range).roundToInt()) }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(thumbOffset)
                .width(TOUCH_WIDTH)
                .height(THUMB_HEIGHT)
                .graphicsLayer { this.alpha = alpha }
                // Only a visible thumb takes a finger, so the right edge of each row stays tappable otherwise.
                .dragThumb(
                    enabled = shown,
                    key = target,
                    range = range,
                    onStart = {
                        dragFraction = fraction()
                        dragging = true
                    },
                    onMove = onMove,
                    onEnd = { dragging = false },
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp)
                    .width(if (dragging) 8.dp else 5.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colour),
            )
        }
        val text = bubble
        if (dragging && text != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(thumbOffset)
                    .padding(end = TOUCH_WIDTH + 4.dp)
                    .widthIn(max = BUBBLE_MAX_WIDTH)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Vertical drags on the thumb, as a share of the track ([range] pixels), while it's [enabled]. */
private fun Modifier.dragThumb(
    enabled: Boolean,
    key: Any,
    range: Float,
    onStart: () -> Unit,
    onMove: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(key, range) {
        detectVerticalDragGestures(onDragStart = { onStart() }, onDragEnd = onEnd, onDragCancel = onEnd) { change, dy ->
            change.consume()
            onMove(dy / range)
        }
    }
}

private const val HIDE_AFTER_MS = 1_200L
private val THUMB_HEIGHT = 48.dp
private val TOUCH_WIDTH = 24.dp
private val BUBBLE_MAX_WIDTH = 220.dp

/** The row at [index] if it's loaded, without asking Paging to load it (the scroll bar's bubble must not fetch). */
fun <T : Any> LazyPagingItems<T>.peekOrNull(index: Int): T? = if (index in 0 until itemCount) peek(index) else null
