package app.tuned.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.tuned.R
import app.tuned.core.ContentKind
import app.tuned.core.FastScroll
import app.tuned.data.AppSettings
import app.tuned.data.db.ChannelWithNow
import app.tuned.ui.common.ChannelRowSkeleton
import app.tuned.ui.common.FastScrollbar
import app.tuned.ui.common.SKELETON_ROWS
import app.tuned.ui.common.firstLoad
import app.tuned.ui.common.focusRing
import app.tuned.ui.common.peekOrNull
import app.tuned.ui.common.rememberFastScroll
import app.tuned.ui.common.rememberTvFocus
import app.tuned.ui.common.tvFocusItem
import app.tuned.ui.common.tvPagedList
import app.tuned.ui.guide.GuideGridView

/** Live TV as the channel list, or as the grid guide when that's switched on. */
@Composable
internal fun LiveTab(
    vm: BrowseViewModel,
    settings: AppSettings,
    onPlay: (Long) -> Unit,
    onOpenGuide: (Long) -> Unit,
    onSearchEverything: (String) -> Unit,
    /** Changes when focus should come back to the list on a TV, such as the pane beside it closing. */
    focusReturn: Int = 0,
    /** On a TV, the channel the remote moves onto, for the details beside the list. */
    onFocusChannel: ((ChannelWithNow) -> Unit)? = null,
) {
    if (settings.liveGrid) {
        val channels = vm.channels.collectAsLazyPagingItems()
        Box(Modifier.fillMaxSize()) {
            GuideGridView(channels, settings.guideDays, onPlay, onOpenGuide)
            NoMatches(channels, vm, ContentKind.LIVE, onSearchEverything)
            NoQuickMatches(channels, vm)
        }
    } else {
        LiveList(
            vm,
            settings.showGuideInList,
            settings.channelNumbers,
            settings.alwaysShowScrollbar,
            onPlay,
            onOpenGuide,
            onSearchEverything,
            focusReturn,
            onFocusChannel,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveList(
    vm: BrowseViewModel,
    showGuide: Boolean,
    showNumbers: Boolean,
    alwaysShowScrollbar: Boolean,
    onPlay: (Long) -> Unit,
    onOpenGuide: (Long) -> Unit,
    onSearchEverything: (String) -> Unit,
    focusReturn: Int,
    onFocusChannel: ((ChannelWithNow) -> Unit)?,
) {
    val channels = vm.channels.collectAsLazyPagingItems()
    val favourites by vm.favouriteIds.collectAsStateWithLifecycle()
    val picker = rememberListPicker()
    val editPicker = rememberChannelEditPicker()
    val state = rememberLazyListState()
    val focus = rememberTvFocus(focusReturn)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().tvPagedList(focus, state),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (channels.firstLoad) items(SKELETON_ROWS) { ChannelRowSkeleton() }
            items(count = channels.itemCount, key = channels.itemKey { it.id }) { index ->
                val channel = channels[index]
                if (channel == null) {
                    ChannelRowSkeleton()
                } else {
                    val favourite = channel.id in favourites
                    ChannelRow(
                        modifier =
                            Modifier.tvFocusItem(focus, channel.id, first = index == 0).then(
                                if (onFocusChannel ==
                                    null
                                ) {
                                    Modifier
                                } else {
                                    Modifier.onFocusChanged { if (it.hasFocus) onFocusChannel(channel) }
                                },
                            ),
                        channel = channel,
                        favourite = favourite,
                        showGuide = showGuide,
                        showNumber = showNumbers,
                        onPlay = { onPlay(channel.id) },
                        onGuide = { onOpenGuide(channel.id) },
                        onToggleFavourite = { vm.toggleFavourite(channel.id, favourite) },
                        onAddToList = { picker.open(channel.id) },
                        onRename = { editPicker.rename(channel.id) },
                        onChangeLogo = { editPicker.changeLogo(channel.id) },
                    )
                }
            }
        }
        FastScrollbar(rememberFastScroll(state), alwaysShowScrollbar) { index ->
            channels.peekOrNull(index)?.let { FastScroll.label(it.groupName, if (showNumbers) it.number else 0) }
        }
        NoMatches(channels, vm, ContentKind.LIVE, onSearchEverything)
        NoQuickMatches(channels, vm)
    }
}

/** One tap to favourite a channel or take it off: a filled heart in the accent colour when it is a favourite. */
@Composable
internal fun FavouriteButton(
    favourite: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        if (favourite) {
            Icon(Icons.Filled.Favorite, stringResource(R.string.favourite_remove), tint = MaterialTheme.colorScheme.primary)
        } else {
            Icon(Icons.Filled.FavoriteBorder, stringResource(R.string.favourite_add))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: ChannelWithNow,
    favourite: Boolean,
    showGuide: Boolean,
    showNumber: Boolean,
    onPlay: () -> Unit,
    onGuide: () -> Unit,
    onToggleFavourite: () -> Unit,
    onAddToList: () -> Unit,
    onRename: () -> Unit,
    onChangeLogo: () -> Unit,
    /** On a TV, where the remote's focus comes back to ([tvFocusItem]). */
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    // Each channel is a rounded card, like a row on a modern website.
    Box(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier
                .fillMaxWidth()
                .focusRing(MaterialTheme.shapes.medium, MaterialTheme.colorScheme.primary)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.a11y_play),
                    onClick = onPlay,
                    onLongClickLabel = stringResource(R.string.a11y_more_options),
                    onLongClick = { menu = true },
                ).padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(channel.logo, Modifier.size(width = 64.dp, height = 44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showNumber && channel.number > 0) {
                        Text(
                            channel.number.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                val now = channel.nowTitle
                if (showGuide && now != null) {
                    Text(
                        now,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val start = channel.nowStart ?: 0
                    val stop = channel.nowStop ?: 0
                    if (stop > start) {
                        val progress = ((System.currentTimeMillis() - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier =
                                Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth(0.6f)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            drawStopIndicator = {},
                        )
                    }
                }
            }
            FavouriteButton(favourite, onToggleFavourite)
            // Every channel has its page (its guide, with add to list, rename and change logo as buttons), so what the long
            // press offers is also one press away on a remote, guide or not.
            IconButton(onClick = onGuide) {
                // Named with the channel, so a screen reader's list of buttons doesn't read "Guide" a hundred times.
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.a11y_guide_for, channel.name))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (favourite) R.string.favourite_remove else R.string.favourite_add)) },
                onClick = {
                    menu = false
                    onToggleFavourite()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.list_add)) },
                onClick = {
                    menu = false
                    onAddToList()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.channel_rename)) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.channel_change_logo)) },
                onClick = {
                    menu = false
                    onChangeLogo()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.guide_open)) },
                onClick = {
                    menu = false
                    onGuide()
                },
            )
        }
    }
}
