package com.tunedplayer.app.ui.guide

import android.app.Application
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.GuideGrid
import com.tunedplayer.app.data.db.ChannelWithNow
import com.tunedplayer.app.data.db.ProgrammeEntity
import com.tunedplayer.app.diagnostics.attempt
import com.tunedplayer.app.ui.browse.Logo
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.rememberTimeLabels
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The grid guide's programmes. Each channel's guide is read from Room in blocks of [GuideGrid.BLOCK] through the
 * (channelId, start) index and kept here, so scrolling across or back reads nothing twice; only the rows on screen
 * ever ask. A finished refresh drops the cache, so the grid shows the new guide.
 */
class GuideGridViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val container = app.container
    private val programmes = container.db.programmes()
    private val cache = LruCache<String, List<ProgrammeEntity>>(CACHE_BLOCKS)

    /** Goes up each time a refresh ends, so the rows read the new guide. */
    val generation: StateFlow<Int> =
        container.scheduler.refreshing
            .distinctUntilChanged()
            .runningFold(0 to false) { (count, was), now -> (if (was && !now) count + 1 else count) to now }
            .map { it.first }
            .distinctUntilChanged()
            .onEach { cache.evictAll() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** The longest catch-up any channel keeps, in days, so the grid scrolls back to those recordings. */
    suspend fun catchUpDays(): Int = attempt(TAG, "catch-up days") { container.db.catchUp().maxDays() }.getOrNull() ?: 0

    /** The blocks already read, joined, or null if any is missing. */
    fun cached(
        channelId: String,
        blocks: LongRange,
    ): List<ProgrammeEntity>? = join(blocks.map { cache.get(key(channelId, it)) ?: return null })

    suspend fun load(
        channelId: String,
        blocks: LongRange,
    ): List<ProgrammeEntity> = join(
        blocks.map { block ->
            cache.get(key(channelId, block)) ?: attempt(TAG, "guide block") {
                programmes.between(channelId, block * GuideGrid.BLOCK, (block + 1) * GuideGrid.BLOCK)
            }.getOrNull()?.also { cache.put(key(channelId, block), it) }.orEmpty()
        },
    )

    // A programme running across a block boundary is in both blocks.
    private fun join(parts: List<List<ProgrammeEntity>>): List<ProgrammeEntity> =
        if (parts.size == 1) parts[0] else parts.flatten().distinctBy { it.rowId }

    private fun key(
        channelId: String,
        block: Long,
    ) = "$block|$channelId"

    private companion object {
        const val TAG = "GuideGrid"

        /** About 40 rows of three blocks each: a few screens of channels either way. */
        const val CACHE_BLOCKS = 120
    }
}

private val ROW_HEIGHT = 64.dp
private val HEADER_HEIGHT = 36.dp

/** Pixels per minute of the timeline; wider screens show more time rather than bigger cells. */
private const val DP_PER_MINUTE = 3.2f
private const val WIDE_DP = 840

/** How much off-screen time each row lays out on either side, so a remote can move onto the next programme. */
private const val MARGIN = GuideGrid.HOUR

/**
 * Live TV as a grid: channels down the side, time across the top, like a TV guide. The channels are the same paged
 * list as the Live TV list (same groups, favourites and search); each row reads only the programmes in view. A swipe
 * across moves every row together. Tapping what's on now plays it; anything else opens that channel's guide, where
 * reminders and catch-up are.
 */
@Composable
fun GuideGridView(
    channels: LazyPagingItems<ChannelWithNow>,
    guideDays: Int,
    onPlay: (Long) -> Unit,
    onOpenGuide: (Long) -> Unit,
    grid: GuideGridViewModel = viewModel(),
) {
    val generation by grid.generation.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val catchUpDays by produceState(0) { value = grid.catchUpDays() }
    val earliest = GuideGrid.earliest(now, catchUpDays)
    val latest = GuideGrid.latest(now, guideDays)
    // The view's start: minutes after an anchor (the half hour the grid first opened on), kept across rotation and
    // tab switches. Minutes since the epoch would be too big for a Float to hold to the minute.
    val anchor by rememberSaveable { mutableLongStateOf(GuideGrid.slotStart(System.currentTimeMillis())) }
    // Read only inside lambdas and derived state, so a swipe doesn't recompose the grid frame by frame.
    val offset = rememberSaveable { mutableFloatStateOf(0f) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth.value >= WIDE_DP
        val channelWidth = if (wide) 200.dp else 116.dp
        val density = LocalDensity.current
        val pxPerMinute = with(density) { DP_PER_MINUTE.dp.toPx() }
        val visibleMinutes = ((maxWidth - channelWidth).value / DP_PER_MINUTE).coerceAtLeast(30f)
        val visible = (visibleMinutes * GuideGrid.MINUTE).toLong()
        val minutesOf: (Long) -> Float = { t -> (t - anchor) / GuideGrid.MINUTE.toFloat() }
        val viewStartOf: () -> Long = { anchor + (offset.floatValue * GuideGrid.MINUTE).toLong() }
        val minOffset = minutesOf(earliest)
        val maxOffset = maxOf(minOffset, minutesOf(GuideGrid.clampStart(latest, earliest, latest, visible)))
        val scroll =
            rememberScrollableState { delta ->
                val old = offset.floatValue
                val new = (old - delta / pxPerMinute).coerceIn(minOffset, maxOffset)
                offset.floatValue = new
                (old - new) * pxPerMinute
            }
        val scope = rememberCoroutineScope()
        // The half hour the view starts in: the grid recomposes only when it changes; in between, cells just move.
        val viewSlot by remember(anchor) { derivedStateOf { GuideGrid.slotStart(viewStartOf()) } }
        val windowFrom = viewSlot - MARGIN
        val windowTo = GuideGrid.slotStart(viewSlot + visible) + 2 * GuideGrid.SLOT + MARGIN
        val xOf: (Long) -> Int =
            remember(anchor, pxPerMinute) { { t -> ((minutesOf(t) - offset.floatValue) * pxPerMinute).roundToInt() } }
        val scrollTo: (Long) -> Unit =
            remember(anchor, pxPerMinute, earliest, latest, visible) {
                { target ->
                    val clamped = GuideGrid.clampStart(target, earliest, latest, visible)
                    scope.launch { scroll.animateScrollBy((offset.floatValue - minutesOf(clamped)) * pxPerMinute) }
                }
            }
        val reveal: (Long) -> Unit =
            remember(scrollTo) {
                { start ->
                    val viewStart = viewStartOf()
                    val target = GuideGrid.reveal(start, viewStart, visible)
                    if (target != viewStart) scrollTo(target)
                }
            }
        val jumpToNow: () -> Unit = { scrollTo(GuideGrid.slotStart(now)) }

        Column(Modifier.fillMaxSize().scrollable(scroll, Orientation.Horizontal)) {
            TimeHeader(channelWidth, viewSlot, visible, windowFrom, windowTo, now, xOf, jumpToNow)
            Box(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                        val channel = channels[index]
                        if (channel == null) {
                            Spacer(Modifier.fillMaxWidth().height(ROW_HEIGHT))
                        } else {
                            Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
                                ChannelCell(channel, channelWidth) { onPlay(channel.id) }
                                ProgrammeRow(
                                    channel = channel,
                                    grid = grid,
                                    generation = generation,
                                    windowFrom = windowFrom,
                                    windowTo = windowTo,
                                    now = now,
                                    xOf = xOf,
                                    pxPerMinute = pxPerMinute,
                                    onPlay = { onPlay(channel.id) },
                                    onOpenGuide = { onOpenGuide(channel.id) },
                                    onFocus = reveal,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                NowLine(channelWidth, now, xOf)
            }
        }
    }
}

@Composable
private fun TimeHeader(
    channelWidth: Dp,
    viewStart: Long,
    visible: Long,
    windowFrom: Long,
    windowTo: Long,
    now: Long,
    xOf: (Long) -> Int,
    onNow: () -> Unit,
) {
    val times = rememberTimeLabels()
    val day =
        stringResource(
            R.string.grid_day_zone,
            if (times.isToday(viewStart, now)) stringResource(R.string.grid_today) else times.day(viewStart),
            times.zoneName(viewStart),
        )
    val showingNow = now in viewStart..(viewStart + visible)
    Row(Modifier.fillMaxWidth().height(HEADER_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        // The corner says which day is in view; when "now" has scrolled away, it brings it back.
        Box(
            Modifier
                .width(channelWidth)
                .fillMaxHeight()
                .focusRing(RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primary)
                .clickable(onClick = onNow)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                if (showingNow) day else stringResource(R.string.grid_now),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            for (tick in GuideGrid.ticks(windowFrom, windowTo)) {
                Text(
                    times.time(tick),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(xOf(tick), 0) }
                            .padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ChannelCell(
    channel: ChannelWithNow,
    width: Dp,
    onPlay: () -> Unit,
) {
    Row(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .focusRing(RoundedCornerShape(8.dp), MaterialTheme.colorScheme.primary)
            .clickable(onClick = onPlay)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(channel.logo, Modifier.size(36.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            channel.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgrammeRow(
    channel: ChannelWithNow,
    grid: GuideGridViewModel,
    generation: Int,
    windowFrom: Long,
    windowTo: Long,
    now: Long,
    xOf: (Long) -> Int,
    pxPerMinute: Float,
    onPlay: () -> Unit,
    onOpenGuide: () -> Unit,
    onFocus: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tvgId = channel.tvgId
    val blocks = GuideGrid.blocks(windowFrom, windowTo)
    // A block read before shows at once; otherwise the row fills in a moment later without blanking what it had.
    val programmes by produceState<List<ProgrammeEntity>?>(
        tvgId?.let { grid.cached(it, blocks) },
        tvgId,
        blocks.first,
        blocks.last,
        generation,
    ) {
        value = tvgId?.let { grid.cached(it, blocks) ?: grid.load(it, blocks) } ?: emptyList()
    }
    val shown =
        remember(programmes, windowFrom, windowTo) {
            programmes?.let { GuideGrid.overlapping(it, windowFrom, windowTo, { p -> p.start }, { p -> p.stop }) }
        }
    val density = LocalDensity.current
    Box(modifier.fillMaxHeight().clipToBounds()) {
        if (shown != null && shown.isEmpty()) {
            // No guide for this channel: one cell across the row that plays it.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .focusRing(RoundedCornerShape(6.dp), MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onPlay)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(R.string.grid_no_guide),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        shown?.forEach { p ->
            // Keyed, so a remote's focus stays on its programme as the row's window moves.
            key(p.rowId) {
                val widthDp = with(density) { ((p.stop - p.start) / GuideGrid.MINUTE.toFloat() * pxPerMinute).toDp() }
                ProgrammeCell(
                    programme = p,
                    now = now,
                    width = widthDp,
                    x = { xOf(p.start) },
                    onClick = { if (p.start <= now && p.stop > now) onPlay() else onOpenGuide() },
                    onFocus = { onFocus(p.start) },
                )
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    programme: ProgrammeEntity,
    now: Long,
    width: Dp,
    x: () -> Int,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    val times = rememberTimeLabels()
    val onNow = programme.start <= now && programme.stop > now
    val onNowLabel = stringResource(R.string.a11y_on_now)
    val past = programme.stop <= now
    val colours = MaterialTheme.colorScheme
    val background =
        when {
            onNow -> colours.primaryContainer
            past -> colours.surfaceContainerLow
            else -> colours.surfaceContainerHigh
        }
    val text =
        when {
            past -> colours.onSurfaceVariant
            onNow -> colours.onPrimaryContainer
            else -> colours.onSurface
        }
    Box(
        Modifier
            .offset { IntOffset(x(), 0) }
            .width(width)
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .focusRing(RoundedCornerShape(6.dp), colours.primary)
            .onFocusChanged { if (it.isFocused) onFocus() }
            // The cell's colour marks what's on now; a screen reader hears it, and what a tap does.
            .clickable(onClickLabel = stringResource(if (onNow) R.string.a11y_play else R.string.a11y_details), onClick = onClick)
            .semantics { if (onNow) stateDescription = onNowLabel },
    ) {
        // A programme that started before the view keeps its title in sight at the left edge, as long as it fits.
        Column(
            Modifier
                .offset { IntOffset((-x()).coerceAtLeast(0), 0) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                programme.title,
                style = MaterialTheme.typography.bodyMedium,
                color = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.grid_time_range, times.time(programme.start), times.time(programme.stop)),
                style = MaterialTheme.typography.bodySmall,
                color = colours.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A line down the grid at the current time. */
@Composable
private fun NowLine(
    channelWidth: Dp,
    now: Long,
    xOf: (Long) -> Int,
) {
    Box(Modifier.fillMaxSize().padding(start = channelWidth).clipToBounds()) {
        Box(
            Modifier
                .offset { IntOffset(xOf(now), 0) }
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
