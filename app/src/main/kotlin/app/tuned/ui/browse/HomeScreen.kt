package app.tuned.ui.browse

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.R
import app.tuned.container
import app.tuned.core.ContentKind
import app.tuned.core.ScreenLayout
import app.tuned.data.AppSettings
import app.tuned.data.CastMember
import app.tuned.data.StartTab
import app.tuned.data.SyncProgress
import app.tuned.data.db.ProfileEntity
import app.tuned.ui.SyncText
import app.tuned.ui.common.VoiceSearchButton
import app.tuned.ui.common.windowWidthDp
import app.tuned.ui.offline.DownloadsViewModel
import app.tuned.ui.settings.ProfileBadge
import app.tuned.updates.GitHubUpdates

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
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit,
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
    val select: (Int) -> Unit = { index ->
        selected = index
        vm.rememberTab(index)
    }

    Row(Modifier.fillMaxSize()) {
        if (rail) HomeRail(selected, select, onOpenDownloads)
        Scaffold(
            modifier = Modifier.weight(1 - ScreenLayout.DETAIL_FRACTION),
            floatingActionButton = {
                // Auto-tune: with a search on Live TV (a channel or an event, say "UFC"), find one that works and play it.
                if (tab.kind == ContentKind.LIVE && liveSearch.isNotBlank()) {
                    ExtendedFloatingActionButton(
                        onClick = { onTune(liveSearch.trim()) },
                        icon = { Icon(Icons.Filled.Radar, contentDescription = null) },
                        text = { Text(stringResource(R.string.tune_button)) },
                    )
                }
            },
            topBar = {
                val search = vm.search(tab.kind)
                val text by search.collectAsStateWithLifecycle()
                SearchBarRow(
                    text = text,
                    hint = stringResource(tab.search),
                    onChange = { search.value = it },
                    grid = if (tab.kind == ContentKind.LIVE) settings.liveGrid else null,
                    onToggleGrid = { vm.setLiveGrid(!settings.liveGrid) },
                    onOpenSearch = onOpenSearch,
                    // A kids profile has no way into Settings; switching profile (with a PIN) is the way out.
                    onOpenSettings = onOpenSettings.takeUnless { settings.kidsProfile },
                    profile = profile.takeIf { settings.profileCount > 1 },
                    onOpenProfiles = onOpenProfiles,
                    onOpenEvents = onOpenEvents.takeIf { tab.kind == ContentKind.LIVE },
                )
            },
            bottomBar = {
                if (!rail) {
                    NavigationBar {
                        tabs.forEachIndexed { index, t ->
                            NavigationBarItem(
                                selected = index == selected,
                                onClick = { select(index) },
                                icon = { Icon(t.icon, contentDescription = null) },
                                label = { Text(stringResource(t.label)) },
                            )
                        }
                        DownloadsNavItem(onOpenDownloads)
                    }
                }
            },
        ) { padding ->
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
                progress?.let { RefreshBar(it) }
                UpdateBanner()
                CategoryChips(vm, tab.kind)
                val lists: @Composable () -> Unit = {
                    when (tab.kind) {
                        ContentKind.LIVE -> LiveTab(vm, settings, onPlay, openGuide)
                        ContentKind.MOVIE -> MovieGrid(vm, onPlay, openMovie)
                        ContentKind.SERIES -> ShowGrid(vm, openShow, onPlay)
                    }
                }
                // Pull to refresh is a setting, off by default; Settings › Refresh now always works.
                if (settings.pullToRefresh) {
                    PullToRefreshBox(
                        isRefreshing = refreshing && progress == null,
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

/** A newer Tuned was downloaded from GitHub: one tap installs it. Nothing shows otherwise. */
@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val updates = context.container.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val ready = state as? GitHubUpdates.State.Ready ?: return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.update_ready, ready.versionName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { updates.install(context) }) { Text(stringResource(R.string.update_install)) }
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
    /** Live Events, shown on the Live TV tab only. */
    onOpenEvents: (() -> Unit)?,
) {
    val switchLabel = stringResource(R.string.profile_switch)
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
                modifier = Modifier.weight(1f).height(52.dp),
            )
            // Live TV only: switch between the channel list and the grid guide.
            if (grid != null) {
                IconButton(onClick = onToggleGrid) {
                    Icon(
                        if (grid) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                        contentDescription = stringResource(if (grid) R.string.grid_hide else R.string.grid_show),
                    )
                }
            }
            if (onOpenEvents != null) {
                IconButton(onClick = onOpenEvents) {
                    Icon(Icons.Filled.EmojiEvents, contentDescription = stringResource(R.string.events_title))
                }
            }
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.TravelExplore, contentDescription = stringResource(R.string.search_everything))
            }
            if (profile != null) {
                IconButton(onClick = onOpenProfiles) {
                    Box(Modifier.semantics { contentDescription = switchLabel }) { ProfileBadge(profile, size = 28.dp) }
                }
            }
            if (onOpenSettings != null) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                }
            }
        }
    }
}

@Composable
private fun CategoryChips(
    vm: BrowseViewModel,
    kind: ContentKind,
) {
    val groups by vm.groups(kind).collectAsStateWithLifecycle()
    val filterFlow = vm.filter(kind)
    val filter by filterFlow.collectAsStateWithLifecycle()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
            val context = LocalContext.current
            val hiddenMessage = stringResource(R.string.group_hidden, g.groupName)
            FilterChip(
                selected = on,
                onClick = { filterFlow.value = if (on) Filter.All else Filter.Group(g.groupName) },
                label = { Text(g.groupName) },
                // The chosen group can be hidden from here; Settings › Hidden groups brings it back.
                trailingIcon =
                    if (on) {
                        {
                            Icon(
                                Icons.Filled.VisibilityOff,
                                contentDescription = stringResource(R.string.group_hide),
                                modifier =
                                    Modifier.size(18.dp).clickable {
                                        vm.hideGroup(kind, g.groupName)
                                        Toast
                                            .makeText(
                                                context,
                                                hiddenMessage,
                                                Toast.LENGTH_LONG,
                                            ).show()
                                    },
                            )
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
                error,
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

/** Downloads, beside the three sections: opens its own page, with a badge while anything is still to download. */
@Composable
private fun RowScope.DownloadsNavItem(onClick: () -> Unit) {
    NavigationBarItem(
        selected = false,
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
