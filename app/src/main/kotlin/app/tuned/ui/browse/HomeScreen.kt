package app.tuned.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.People
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.core.ContentKind
import app.tuned.core.PinnedGroups
import app.tuned.core.ScreenLayout
import app.tuned.data.AppSettings
import app.tuned.data.CastMember
import app.tuned.data.StartTab
import app.tuned.data.SyncProgress
import app.tuned.data.db.ProfileEntity
import app.tuned.diagnostics.DeviceClock
import app.tuned.ui.SyncText
import app.tuned.ui.common.UpdateBanner
import app.tuned.ui.common.VoiceSearchButton
import app.tuned.ui.common.focusRing
import app.tuned.ui.common.windowWidthDp
import app.tuned.ui.offline.DownloadsScreen
import app.tuned.ui.offline.DownloadsViewModel
import app.tuned.ui.settings.ProfileBadge
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
    val liveSearch by vm.search(ContentKind.LIVE).collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val widthDp = windowWidthDp()
    val rail = ScreenLayout.rail(widthDp)
    val panes = ScreenLayout.panes(widthDp)
    // On a wide screen a film, show or guide opens beside the list; on anything narrower it opens full screen.
    var detail by rememberSaveable(stateSaver = Detail.saver) { mutableStateOf<Detail?>(null) }
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
    BackHandler(enabled = detail != null) { detail = null }
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
        if (rail) HomeRail(selected.takeUnless { downloads }, select, openDownloads)
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
                    // The words typed on the tab carry over, so the search isn't typed twice.
                    onOpenSearch = { onOpenSearch(text.trim()) },
                    // A kids profile has no way into Settings; switching profile (with a PIN) is the way out.
                    onOpenSettings = onOpenSettings.takeUnless { settings.kidsProfile },
                    profile = profile.takeIf { settings.profileCount > 1 },
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
                    }
                }
            },
        ) { padding ->
            if (downloads) {
                // Its own title bar sits under the space Home's Scaffold already left for the status bar.
                Box(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    DownloadsScreen(onPlay = onPlay, onBack = null)
                }
                return@Scaffold
            }
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (empty == true) {
                    FirstRun(
                        refreshing = refreshing,
                        progress = progress,
                        error = lastError,
                        onRetry = vm::refresh,
                        onChangeLogin = onOpenPlaylists,
                    )
                    return@Column
                }
                requestedProgress?.let { RefreshBar(it) }
                UpdateBanner(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                CategoryChips(vm, tab.kind, onGroupHidden, onOpenEvents.takeIf { tab.kind == ContentKind.LIVE })
                val lists: @Composable () -> Unit = {
                    when (tab.kind) {
                        ContentKind.LIVE -> LiveTab(vm, settings, onPlay, openGuide, onOpenSearch)
                        ContentKind.MOVIE -> MovieGrid(vm, onPlay, openMovie, settings.alwaysShowScrollbar, onOpenSearch)
                        ContentKind.SERIES -> ShowGrid(vm, openShow, onPlay, settings.alwaysShowScrollbar, onOpenSearch)
                    }
                }
                // Pull to refresh is a setting, off by default; Settings › Refresh now always works.
                if (settings.pullToRefresh) {
                    PullToRefreshBox(
                        isRefreshing = requestedRefreshing && requestedProgress == null,
                        onRefresh = vm::refresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        lists()
                    }
                } else {
                    Box(Modifier.fillMaxSize()) { lists() }
                }
            }
        }
        val open = detail
        if (panes && open != null) {
            VerticalDivider()
            Box(Modifier.weight(ScreenLayout.DETAIL_FRACTION).fillMaxHeight()) {
                DetailPane(open, onPlay = onPlay, onClose = { detail = null }, onOpenPerson = onOpenPerson)
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    text: String,
    hint: String,
    onChange: (String) -> Unit,
    grid: Boolean?,
    onToggleGrid: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    profile: ProfileEntity?,
    onOpenProfiles: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
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
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
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
                shape = RoundedCornerShape(28.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            )
            // One button beside the box, so search keeps the width (2026-11-18, UI review): the profile's badge when
            // there are profiles, otherwise the three dots. Everything else that used to sit here is in its menu.
            val more = stringResource(R.string.home_menu)
            Box {
                if (profile != null) {
                    // As tall as the search box beside it (Melvin, 2026-11-19: "make the user button bigger"). The ring
                    // shows on the outer edge while a TV remote is on it, clear of the picture inside.
                    Box(
                        Modifier
                            .padding(start = 8.dp, end = 4.dp)
                            .size(PROFILE_BUTTON)
                            .clip(CircleShape)
                            .focusRing(CircleShape, MaterialTheme.colorScheme.primary)
                            .clickable(role = Role.Button) { menu = true }
                            .semantics { contentDescription = more },
                        contentAlignment = Alignment.Center,
                    ) {
                        ProfileBadge(profile, size = PROFILE_BADGE)
                    }
                } else {
                    IconButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = more }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuRow(Icons.Filled.TravelExplore, stringResource(R.string.search_everything)) {
                        menu = false
                        onOpenSearch()
                    }
                    // Live TV only: switch between the channel list and the grid guide.
                    if (grid != null) {
                        MenuRow(
                            if (grid) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                            stringResource(if (grid) R.string.grid_hide else R.string.grid_show),
                        ) {
                            menu = false
                            onToggleGrid()
                        }
                    }
                    if (profile != null) {
                        MenuRow(Icons.Filled.People, stringResource(R.string.profile_switch)) {
                            menu = false
                            onOpenProfiles()
                        }
                    }
                    if (onOpenSettings != null) {
                        MenuRow(Icons.Filled.Settings, stringResource(R.string.settings)) {
                            menu = false
                            onOpenSettings()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun CategoryChips(
    vm: BrowseViewModel,
    kind: ContentKind,
    /** The kind, the group and the message that says it is hidden. */
    onHidden: (ContentKind, String, String) -> Unit,
    /** Live events, first in Live TV's row (it used to be an icon beside search). Null elsewhere. */
    onOpenEvents: (() -> Unit)?,
) {
    val groups by vm.groups(kind).collectAsStateWithLifecycle()
    val lists by vm.lists(kind).collectAsStateWithLifecycle()
    val filterFlow = vm.filter(kind)
    val filter by filterFlow.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                // The small eye inside the chip is hard to reach with a screen reader, so hiding is also an action on the chip.
                modifier =
                    if (on) {
                        Modifier.semantics {
                            customActions =
                                listOf(
                                    CustomAccessibilityAction(pinLabel) {
                                        pin()
                                        true
                                    },
                                    CustomAccessibilityAction(hideLabel) {
                                        hide()
                                        true
                                    },
                                )
                        }
                    } else {
                        Modifier
                    },
                // The chosen group can be hidden from here; Settings › Hidden groups brings it back.
                trailingIcon =
                    if (on) {
                        {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(
                                    if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = pinLabel,
                                    modifier = Modifier.size(18.dp).clickable(onClick = pin),
                                )
                                Icon(
                                    Icons.Filled.VisibilityOff,
                                    contentDescription = stringResource(R.string.group_hide),
                                    modifier =
                                        Modifier.size(18.dp).clickable(onClick = hide),
                                )
                            }
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun FirstRun(
    refreshing: Boolean,
    progress: SyncProgress?,
    error: String,
    onRetry: () -> Unit,
    onChangeLogin: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (refreshing || error.isBlank()) {
            Text(stringResource(R.string.first_run_loading), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            SyncProgressBar(progress, Modifier.fillMaxWidth().widthIn(max = 420.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                progress?.let { SyncText.describe(LocalContext.current, it) } ?: stringResource(R.string.login_downloading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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

/** Determinate while the size is known, otherwise the moving bar. */
@Composable
private fun SyncProgressBar(
    progress: SyncProgress?,
    modifier: Modifier = Modifier,
) {
    val fraction = progress?.fraction
    val shaped = modifier.height(6.dp).clip(RoundedCornerShape(3.dp))
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = shaped, drawStopIndicator = {})
    } else {
        LinearProgressIndicator(modifier = shaped)
    }
}

/** A thin bar and one line above the lists while a refresh downloads, so a slow server is never a mystery. */
@Composable
private fun RefreshBar(progress: SyncProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            SyncText.describe(LocalContext.current, progress),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SyncProgressBar(progress, Modifier.fillMaxWidth())
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

/** The profile button beside the search box: its height (56dp, a Material text field's) and the picture inside it. */
private val PROFILE_BUTTON = 56.dp
private val PROFILE_BADGE = 48.dp
