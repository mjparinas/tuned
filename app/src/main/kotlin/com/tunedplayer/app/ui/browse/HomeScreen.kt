package com.tunedplayer.app.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.R
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.PinnedGroups
import com.tunedplayer.app.core.ScreenLayout
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.CastMember
import com.tunedplayer.app.data.StartTab
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.data.db.ChannelWithNow
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.diagnostics.DeviceClock
import com.tunedplayer.app.ui.common.UpdateBanner
import com.tunedplayer.app.ui.common.VoiceSearchButton
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.isTelevision
import com.tunedplayer.app.ui.common.windowWidthDp
import com.tunedplayer.app.ui.offline.DownloadsScreen
import com.tunedplayer.app.ui.offline.DownloadsViewModel
import com.tunedplayer.app.ui.settings.ProfileBadge
import kotlinx.coroutines.launch

internal data class Tab(
    val kind: ContentKind,
    val label: Int,
    val icon: ImageVector,
    val search: Int,
)

internal val tabs =
    listOf(
        Tab(ContentKind.LIVE, R.string.tab_live, Icons.Filled.Tv, R.string.search_live),
        Tab(ContentKind.MOVIE, R.string.tab_movies, Icons.Filled.Movie, R.string.search_movies),
        Tab(ContentKind.SERIES, R.string.tab_shows, Icons.Filled.VideoLibrary, R.string.search_shows),
    )

@Composable
fun HomeScreen(
    settings: AppSettings,
    onPlay: (Long) -> Unit,
    onOpenShow: (String, String) -> Unit,
    onOpenGuide: (Long) -> Unit,
    onOpenMovie: (Long) -> Unit,
    onOpenPerson: (CastMember) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    /** Search everything, starting from the words typed on the tab (empty for none). */
    onOpenSearch: (String) -> Unit,
    onOpenEvents: () -> Unit,
    onTune: (String) -> Unit,
    onOpenPlaylists: () -> Unit,
    vm: BrowseViewModel = viewModel(),
) {
    val start =
        when (settings.startTab) {
            StartTab.LIVE -> 0
            StartTab.MOVIES -> 1
            StartTab.SHOWS -> 2
            StartTab.LAST -> settings.lastTab.coerceIn(0, 2)
        }
    var selected by rememberSaveable { mutableIntStateOf(start) }
    val tab = tabs[selected]
    val empty by vm.empty.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val requestedProgress by vm.requestedProgress.collectAsStateWithLifecycle()
    val requestedRefreshing by vm.requestedRefreshing.collectAsStateWithLifecycle()
    val requestedPending by vm.requestedPending.collectAsStateWithLifecycle()
    val liveSearch by vm.search(ContentKind.LIVE).collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val widthDp = windowWidthDp()
    val tv = isTelevision()
    // A TV always has the rail: everything is on screen there, one press away, with no menu (Melvin, 2026-11-19).
    val rail = tv || ScreenLayout.rail(widthDp)
    val panes = ScreenLayout.panes(widthDp)
    // On a wide screen a film, show or guide opens beside the list; on anything narrower it opens full screen.
    var detail by rememberSaveable(stateSaver = Detail.saver) { mutableStateOf<Detail?>(null) }
    // Home is up: background work may start a moment later (docs/decisions/speed.md › Quiet start).
    LaunchedEffect(Unit) { vm.homeShown() }
    LaunchedEffect(panes) {
        // Folded, rotated or put in split screen with a pane open: carry on with that page full screen.
        val open = detail
        if (!panes && open != null) {
            detail = null
            when (open.kind) {
                Detail.Kind.MOVIE -> onOpenMovie(open.id)
                Detail.Kind.SHOW -> onOpenShow(open.showKey, open.title)
                Detail.Kind.GUIDE -> onOpenGuide(open.id)
            }
        }
    }
    // The channel under the remote's focus in Live TV on a TV, for the details beside the list.
    var focusedChannel by remember { mutableStateOf<ChannelWithNow?>(null) }
    // On a TV, closing the pane puts the remote's focus back on the item that opened it (ui/common/TvFocus).
    var focusReturn by remember { mutableIntStateOf(0) }
    val closeDetail: () -> Unit = {
        detail = null
        focusReturn += 1
    }
    BackHandler(enabled = detail != null, onBack = closeDetail)
    val openShow: (String, String) -> Unit = if (panes) { key, title -> detail = Detail.show(key, title) } else onOpenShow
    val openMovie: (Long) -> Unit = if (panes) { id -> detail = Detail.movie(id) } else onOpenMovie
    val openGuide: (Long) -> Unit = if (panes) { id -> detail = Detail.guide(id) } else onOpenGuide
    // Downloads is a section of Home like the three tabs, so the bar stays under it; back returns to the tab before.
    var downloads by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = downloads) { downloads = false }
    val openDownloads = { downloads = true }
    val select: (Int) -> Unit = { index ->
        downloads = false
        selected = index
        vm.rememberTab(index)
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.group_hidden_undo)
    // Hiding a category says so with Undo, rather than a toast that can't take it back.
    val onGroupHidden: (ContentKind, String, String) -> Unit = { kind, group, message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result =
                snackbar.showSnackbar(
                    message,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Long,
                )
            if (result == SnackbarResult.ActionPerformed) vm.unhideGroup(kind, group)
        }
    }

    Row(Modifier.fillMaxSize()) {
        // A kids profile has no way into Settings; switching profile (with a PIN) is the way out.
        val openSettings = onOpenSettings.takeUnless { settings.kidsProfile }
        if (rail) {
            HomeRail(
                selected = selected.takeUnless { downloads },
                onSelect = select,
                onOpenDownloads = openDownloads,
                onOpenSettings = openSettings,
                // On a TV the menu's other items move to the rail too.
                tvExtras =
                    if (tv) {
                        RailExtras(
                            onOpenSearch = { onOpenSearch(vm.search(tab.kind).value.trim()) },
                            profile = profile.takeIf { settings.profileCount > 1 },
                            onOpenProfiles = onOpenProfiles,
                        )
                    } else {
                        null
                    },
            )
        }
        Scaffold(
            modifier = Modifier.weight(1 - ScreenLayout.DETAIL_FRACTION),
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                // Auto-tune: with a search on Live TV (a channel or an event, say "UFC"), find one that works and play it.
                if (!downloads && tab.kind == ContentKind.LIVE && liveSearch.isNotBlank()) {
                    ExtendedFloatingActionButton(
                        onClick = { onTune(liveSearch.trim()) },
                        icon = { Icon(Icons.Filled.Radar, contentDescription = null) },
                        text = { Text(stringResource(R.string.tune_button)) },
                    )
                }
            },
            topBar = {
                // Downloads has its own title bar.
                if (downloads) return@Scaffold
                val search = vm.search(tab.kind)
                val text by search.collectAsStateWithLifecycle()
                SearchBarRow(
                    text = text,
                    hint = stringResource(tab.search),
                    onChange = { search.value = it },
                    grid = if (tab.kind == ContentKind.LIVE) settings.liveGrid else null,
                    onToggleGrid = { vm.setLiveGrid(!settings.liveGrid) },
                    // The words typed on the tab carry over, so the search isn't typed twice. A TV has it on the rail.
                    onOpenSearch = { onOpenSearch(text.trim()) }.takeUnless { tv },
                    profile = profile.takeIf { settings.profileCount > 1 && !tv },
                    onOpenProfiles = onOpenProfiles,
                )
            },
            bottomBar = {
                if (!rail) {
                    NavigationBar {
                        tabs.forEachIndexed { index, t ->
                            NavigationBarItem(
                                selected = !downloads && index == selected,
                                onClick = { select(index) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(stringResource(t.label)) },
                            )
                        }
                        DownloadsNavItem(downloads, openDownloads)
                        // Settings is the bar's last item on phones, one tap away rather than in a menu (Melvin, 2026-11-19).
                        if (openSettings != null) {
                            NavigationBarItem(
                                selected = false,
                                onClick = openSettings,
                                icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                label = { Text(stringResource(R.string.settings)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            if (downloads) {
                // Its own title bar sits under the space Home's Scaffold already left for the status bar.
                // The channels getting ready (the first download, or a refresh the person started) show above it as
                // they do on the other tabs (Melvin, 2026-11-21: "I still want to see the fact that the channels are
                // processing on the downloads page").
                Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    val firstRun = empty == true && refreshing
                    when {
                        firstRun && progress != null -> RefreshBar(checkNotNull(progress))
                        firstRun -> RefreshStarting()
                        requestedProgress != null -> RefreshBar(checkNotNull(requestedProgress))
                        requestedPending == true -> RefreshStarting()
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) { DownloadsScreen(onPlay = onPlay, onBack = null) }
                }
                return@Scaffold
            }
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (empty == true) {
                    // No download queued or running and no error saved: it ended without saying why, so say so with
                    // Retry rather than spin on forever (Melvin, 2026-11-20).
                    val ended = !refreshing && requestedPending == false
                    FirstRun(
                        refreshing = refreshing,
                        progress = progress,
                        error = lastError.ifBlank { if (ended) stringResource(R.string.sync_stopped) else "" },
                        onRetry = vm::retryFirst,
                        onChangeLogin = onOpenPlaylists,
                    )
                    return@Column
                }
                // From the tap on, not only once the refresh reports: a refresh waiting to start (for a connection, or for
                // the first screen to settle) says so too (Melvin, 2026-11-21: "There should always be some type of
                // feedback that something's happening").
                when {
                    requestedProgress != null -> RefreshBar(checkNotNull(requestedProgress))
                    requestedPending == true -> RefreshStarting()
                }
                UpdateBanner(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                // Live TV's channel list on a TV uses the room (Melvin, 2026-11-19: "use the big screen"): the groups down
                // the left, the channels in the middle and what the focused channel is showing on the right.
                val tvColumns = tv && tab.kind == ContentKind.LIVE && !settings.liveGrid
                val events = onOpenEvents.takeIf { tab.kind == ContentKind.LIVE }
                if (!tvColumns) CategoryChips(vm, tab.kind, onGroupHidden, events)
                val lists: @Composable () -> Unit = {
                    when (tab.kind) {
                        ContentKind.LIVE -> {
                            LiveTab(
                                vm,
                                settings,
                                onPlay,
                                openGuide,
                                onOpenSearch,
                                focusReturn,
                                onFocusChannel = if (tvColumns) ({ focusedChannel = it }) else null,
                            )
                        }

                        ContentKind.MOVIE -> {
                            MovieGrid(vm, onPlay, openMovie, settings.alwaysShowScrollbar, onOpenSearch, focusReturn)
                        }

                        ContentKind.SERIES -> {
                            ShowGrid(vm, openShow, onPlay, settings.alwaysShowScrollbar, onOpenSearch, focusReturn)
                        }
                    }
                }
                // Pull to refresh is a setting, off by default; Settings › Refresh now always works.
                val body: @Composable () -> Unit = {
                    if (settings.pullToRefresh) {
                        PullToRefreshBox(
                            isRefreshing = (requestedRefreshing || requestedPending == true) && requestedProgress == null,
                            onRefresh = vm::refresh,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            lists()
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) { lists() }
                    }
                }
                if (tvColumns) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        // The details take what the groups and a readable channel list leave, up to a limit, and are
                        // left out on a TV too narrow for them (a pane open beside the list, say).
                        val info = (maxWidth - TV_GROUPS_WIDTH - TV_LIST_MIN_WIDTH).coerceAtMost(TV_INFO_MAX_WIDTH)
                        Row(Modifier.fillMaxSize()) {
                            CategoryChips(
                                vm,
                                tab.kind,
                                onGroupHidden,
                                events,
                                vertical = true,
                                modifier = Modifier.width(TV_GROUPS_WIDTH).fillMaxHeight(),
                            )
                            Box(Modifier.weight(1f)) { body() }
                            if (info >= TV_INFO_MIN_WIDTH) {
                                ChannelInfoPane(
                                    focusedChannel,
                                    Modifier.width(info).fillMaxHeight().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                                )
                            }
                        }
                    }
                } else {
                    body()
                }
            }
        }
        val open = detail
        if (panes && open != null) {
            VerticalDivider()
            Box(Modifier.weight(ScreenLayout.DETAIL_FRACTION).fillMaxHeight()) {
                DetailPane(open, onPlay = onPlay, onClose = closeDetail, onOpenPerson = onOpenPerson)
            }
        }
    }
}

@Composable
internal fun SearchBarRow(
    text: String,
    hint: String,
    onChange: (String) -> Unit,
    grid: Boolean?,
    onToggleGrid: () -> Unit,
    /** Search everything, from the button at the box's start and the keyboard's search key. Null on a TV, where it is on the rail. */
    onOpenSearch: (() -> Unit)?,
    /** The profile now watching, or null with one profile or on a TV (whose rail has it). Its badge opens Who's watching. */
    profile: ProfileEntity?,
    onOpenProfiles: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = text,
                onValueChange = onChange,
                placeholder = { Text(hint) },
                leadingIcon = {
                    // No menu beside search any more (Melvin, 2026-11-19: "avoid 3 dot drop downs"): the icon at the box's
                    // start is the way to search everything, and the keyboard's search key does the same.
                    if (onOpenSearch != null) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.TravelExplore, contentDescription = stringResource(R.string.search_everything))
                        }
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    }
                },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { onChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                        }
                    } else {
                        VoiceSearchButton(onResult = onChange)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onOpenSearch?.invoke() }),
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            )
            // Each button does one thing, in one press: the guide grid on Live TV, and the profile badge opening Who's
            // watching. Settings is in the bottom bar on phones and on the rail elsewhere.
            if (grid != null) GridToggleButton(grid, onToggleGrid)
            if (profile != null) {
                val switchLabel = stringResource(R.string.profile_switch)
                // As tall as the search box beside it (Melvin, 2026-11-19: "make the user button bigger"). The ring
                // shows on the outer edge while a TV remote is on it, clear of the picture inside.
                Box(
                    Modifier
                        .padding(start = 8.dp, end = 4.dp)
                        .size(PROFILE_BUTTON)
                        .clip(CircleShape)
                        .focusRing(CircleShape, MaterialTheme.colorScheme.primary)
                        .clickable(role = Role.Button, onClick = onOpenProfiles)
                        .semantics { contentDescription = switchLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    ProfileBadge(profile, size = PROFILE_BADGE)
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(
    vm: BrowseViewModel,
    kind: ContentKind,
    /** The kind, the group and the message that says it is hidden. */
    onHidden: (ContentKind, String, String) -> Unit,
    /** Live events, first in Live TV's row (it used to be an icon beside search). Null elsewhere. */
    onOpenEvents: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Down the left of Live TV on a TV, instead of a row across the top (Melvin, 2026-11-19: "use the big screen"). */
    vertical: Boolean = false,
) {
    val groups by vm.groups(kind).collectAsStateWithLifecycle()
    val lists by vm.lists(kind).collectAsStateWithLifecycle()
    val filterFlow = vm.filter(kind)
    val filter by filterFlow.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val content: LazyListScope.() -> Unit = {
        if (onOpenEvents != null) {
            item(key = "events") {
                AssistChip(
                    onClick = onOpenEvents,
                    leadingIcon = { Icon(Icons.Filled.EmojiEvents, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(stringResource(R.string.events_title)) },
                )
            }
        }
        // Live TV's quick filters (Sports, PPV, a country or a language) narrow whichever chip is chosen.
        if (kind == ContentKind.LIVE) item(key = "quick") { LiveQuickChip(vm) }
        // Movies and Shows put their sort first; it orders whichever chip is chosen.
        if (kind != ContentKind.LIVE) item(key = "sort") { SortChip(vm, kind) }
        item(key = "all") {
            FilterChip(selected = filter == Filter.All, onClick = {
                filterFlow.value = Filter.All
            }, label = { Text(stringResource(R.string.filter_all)) })
        }
        item(key = "fav") {
            FilterChip(
                selected = filter == Filter.Favourites,
                onClick = { filterFlow.value = Filter.Favourites },
                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(stringResource(R.string.filter_favourites)) },
            )
        }
        // The person's own lists come straight after Favourites, the built-in one (Live TV and Movies).
        items(lists, key = { "l:" + it.id }) { list ->
            val on = filter == Filter.OwnList(list.id)
            OwnListChip(
                list = list,
                selected = on,
                onSelect = { filterFlow.value = if (on) Filter.All else Filter.OwnList(list.id) },
                onRename = { vm.renameList(list.id, it) },
                onDelete = { vm.deleteList(list.id) },
            )
        }
        if (kind == ContentKind.LIVE) {
            item(key = "recent") {
                FilterChip(
                    selected = filter == Filter.Recent,
                    onClick = { filterFlow.value = if (filter == Filter.Recent) Filter.All else Filter.Recent },
                    leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text(stringResource(R.string.filter_recent)) },
                )
            }
        }
        items(groups, key = { "g:" + it.groupName }) { g ->
            val on = filter == Filter.Group(g.groupName)
            val hiddenMessage = stringResource(R.string.group_hidden, g.groupName)
            val hideLabel = stringResource(R.string.group_hide)
            val hide = {
                vm.hideGroup(kind, g.groupName)
                onHidden(kind, g.groupName, hiddenMessage)
            }
            // Pinned groups sit first, in pin order (vm.groups), marked with a pin; the chosen group's pin pins or unpins it.
            val pinned = PinnedGroups.isPinned(pins, kind.code, g.groupName)
            val pinLabel = stringResource(if (pinned) R.string.group_unpin else R.string.group_pin)
            val pin = { vm.togglePin(kind, g.groupName) }
            // A flow row, so in a TV's narrow column Pin and Hide wrap under the group rather than off the edge.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = on,
                    onClick = { filterFlow.value = if (on) Filter.All else Filter.Group(g.groupName) },
                    label = { Text(g.groupName) },
                    leadingIcon =
                        if (pinned && !on) {
                            {
                                Icon(
                                    Icons.Filled.PushPin,
                                    contentDescription = stringResource(R.string.group_pinned),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            null
                        },
                )
                // The chosen group's pin and hide are chips of their own beside it, each a full-size target a remote
                // can land on (they were small icons inside the chip). Settings › Hidden groups brings a group back.
                if (on) {
                    AssistChip(
                        onClick = pin,
                        leadingIcon = {
                            Icon(
                                if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        label = { Text(pinLabel) },
                    )
                    AssistChip(
                        onClick = hide,
                        leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(hideLabel) },
                    )
                }
            }
        }
    }
    // A TV's column: one group under the next, so the arrows go straight down the groups and right into the channels.
    if (vertical) {
        LazyColumn(
            modifier,
            contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    } else {
        LazyRow(
            modifier,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
internal fun FirstRun(
    refreshing: Boolean,
    progress: SyncProgress?,
    error: String,
    onRetry: () -> Unit,
    onChangeLogin: () -> Unit,
) {
    // Centred, and scrolls when the steps still run taller than the screen (very large text). On a short, wide screen
    // (a TV, a phone held sideways) the steps go beside the title, because stacked they don't fit a TV.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val sideBySide = maxWidth >= FIRST_RUN_SIDE_BY_SIDE_WIDTH && maxHeight < FIRST_RUN_STACKED_HEIGHT
        FirstRunBody(refreshing, progress, error, onRetry, onChangeLogin, sideBySide)
    }
}

/** Wide enough for the title beside the steps. */
private val FIRST_RUN_SIDE_BY_SIDE_WIDTH = 600.dp

/** Seven stacked steps with the title, the line under them and the padding need about this much height. */
private val FIRST_RUN_STACKED_HEIGHT = 560.dp

@Composable
private fun FirstRunBody(
    refreshing: Boolean,
    progress: SyncProgress?,
    error: String,
    onRetry: () -> Unit,
    onChangeLogin: () -> Unit,
    sideBySide: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = if (sideBySide) 16.dp else 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (refreshing || error.isBlank()) {
            FirstDownload(progress, sideBySide = sideBySide)
        } else {
            Text(stringResource(R.string.first_run_failed), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                DeviceClock.explain(LocalContext.current, error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            TextButton(onClick = onChangeLogin) { Text(stringResource(R.string.first_run_change_login)) }
        }
    }
}

/**
 * Downloads, beside the three sections: opens in their place with the bar still below, and has a badge while anything
 * is still to download.
 */
@Composable
private fun RowScope.DownloadsNavItem(
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { DownloadsIcon() },
        label = { Text(stringResource(R.string.downloads)) },
    )
}

/** The downloads icon, with how many are still to come. */
@Composable
internal fun DownloadsIcon(downloads: DownloadsViewModel = viewModel()) {
    val pending by downloads.pending.collectAsStateWithLifecycle()
    BadgedBox(badge = { if (pending > 0) Badge { Text("$pending") } }) {
        Icon(Icons.Filled.DownloadForOffline, contentDescription = null)
    }
}

/**
 * Live TV on a TV: the column of groups and the focused channel's details either side of the channels. The list keeps
 * room for a channel's name beside its buttons; the details are left out before the names get cut short.
 */
private val TV_GROUPS_WIDTH = 160.dp
private val TV_LIST_MIN_WIDTH = 340.dp
private val TV_INFO_MIN_WIDTH = 180.dp
private val TV_INFO_MAX_WIDTH = 260.dp

/** The profile button beside the search box: its height (56dp, a Material text field's) and the picture inside it. */
private val PROFILE_BUTTON = 56.dp
private val PROFILE_BADGE = 48.dp

/**
 * Live TV: the list or the guide grid, one press away beside search instead of in a menu. A TV has room for its words;
 * a phone or tablet shows the icon alone, named for a screen reader.
 */
@Composable
private fun GridToggleButton(
    grid: Boolean,
    onToggle: () -> Unit,
) {
    val icon = if (grid) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView
    val label = stringResource(if (grid) R.string.grid_hide else R.string.grid_show)
    if (!isTelevision()) {
        IconButton(onClick = onToggle, modifier = Modifier.padding(start = 4.dp)) { Icon(icon, contentDescription = label) }
        return
    }
    FilledTonalButton(onClick = onToggle, modifier = Modifier.padding(start = 12.dp).heightIn(min = 52.dp)) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
