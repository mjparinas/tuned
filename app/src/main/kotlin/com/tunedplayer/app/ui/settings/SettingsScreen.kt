package com.tunedplayer.app.ui.settings

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tunedplayer.app.BuildConfig
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ScreenLayout
import com.tunedplayer.app.data.AppReset
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.ui.CaptionSettingsRoute
import com.tunedplayer.app.ui.SettingsPageRoute
import com.tunedplayer.app.ui.common.BackTopBar
import com.tunedplayer.app.ui.common.StartFocus
import com.tunedplayer.app.ui.common.focusRing
import com.tunedplayer.app.ui.common.isTelevision
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.common.startFocus
import com.tunedplayer.app.ui.common.windowWidthDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class SettingsViewModel(
    app: Application,
) : AndroidViewModel(app),
    PinActions {
    private val container = app.container

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launchSafely("Settings") { container.settings.update(change) }
    }

    val hasPin = container.settings.hasAdultPin.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** How many profiles there are, for the Profiles row. */
    val profileCount =
        container.profiles.profiles
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    override suspend fun setPin(pin: String) = container.settings.setAdultPin(pin)

    override suspend fun unlock(pin: String): PinResult {
        val throttle = container.profiles.throttle
        if (throttle.waiting()) return PinResult.TooMany
        return throttle.record(container.settings.unlockAdult(pin)).toPinResult()
    }

    fun lockAdult() = container.settings.lockAdult()

    override fun matchesLogin(secret: String): Boolean = container.credentials.matches(secret)

    fun refreshNow() = container.scheduler.refreshNow()

    /** A refresh the person started is waiting to start or running, for "Refresh now" to say so. */
    val refreshPending =
        container.scheduler.requestedPending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    /** What that refresh is doing ("Step 2 of 6 · Processing channels · 45%"), or null before it reports. */
    val refreshProgress =
        container.scheduler.requestedProgress.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    private val historyClearedFlow = MutableStateFlow(false)

    /** "Clear watch history" finished, so its row says so instead of nothing happening. */
    val historyCleared: StateFlow<Boolean> = historyClearedFlow.asStateFlow()

    /** Off also deletes the copies, so the space comes back at once. */
    fun keepPlaylistCopies(on: Boolean) {
        update { it.copy(keepPlaylistCopies = on) }
        if (!on) viewModelScope.launchSafely("Settings") { withContext(Dispatchers.IO) { container.playlistCopies.clear() } }
    }

    fun clearHistory() {
        viewModelScope.launchSafely("Settings") {
            container.db.library().clearHistory()
            historyClearedFlow.value = true
        }
    }

    /** The downloads and recordings a sign-out asks about. */
    suspend fun savedDownloads(): AppReset.Saved = container.reset.saved()

    /**
     * Signing out resets the app to how it was installed ([AppReset]). The reset runs in the app's scope, so leaving the
     * screen can't stop it half way; [onDone] then opens the welcome screen.
     */
    fun signOut(
        keepDownloads: Boolean,
        onDone: () -> Unit,
    ) {
        viewModelScope.launchSafely("Settings") {
            container.appScope.async { container.reset.signOut(keepDownloads) }.await()
            onDone()
        }
    }
}

/**
 * The pages of Settings, each opened from the first screen. A page's rows live in its own file (SettingsPlayback.kt and
 * so on), so features in different areas never edit the same lines. A new page is one entry here, one branch in
 * [SettingsPageContent], and its file.
 */
enum class SettingsPage(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val icon: ImageVector,
    internal val hue: BadgeHue,
    internal val section: SettingsSection,
) {
    PLAYLISTS(
        R.string.settings_page_playlists,
        R.string.settings_page_playlists_summary,
        Icons.AutoMirrored.Filled.PlaylistPlay,
        BadgeHue.BLUE,
        SettingsSection.ACCOUNT,
    ),
    PROFILES(
        R.string.settings_page_profiles,
        R.string.settings_page_profiles_summary,
        Icons.Filled.People,
        BadgeHue.BLUE,
        SettingsSection.ACCOUNT,
    ),
    CHANNELS(
        R.string.settings_page_channels,
        R.string.settings_page_channels_summary,
        Icons.Filled.Tv,
        BadgeHue.VIOLET,
        SettingsSection.WATCHING,
    ),
    GUIDE(
        R.string.settings_page_guide,
        R.string.settings_page_guide_summary,
        Icons.Filled.CalendarMonth,
        BadgeHue.VIOLET,
        SettingsSection.WATCHING,
    ),
    PLAYBACK(
        R.string.settings_playback,
        R.string.settings_page_playback_summary,
        Icons.Filled.PlayCircle,
        BadgeHue.TEAL,
        SettingsSection.WATCHING,
    ),
    RELIABILITY(
        R.string.settings_page_reliability,
        R.string.settings_page_reliability_summary,
        Icons.Filled.Autorenew,
        BadgeHue.RED,
        SettingsSection.WATCHING,
    ),
    APPEARANCE(
        R.string.settings_appearance,
        R.string.settings_page_appearance_summary,
        Icons.Filled.Palette,
        BadgeHue.AMBER,
        SettingsSection.APP,
    ),
    ABOUT(R.string.settings_page_about, R.string.settings_page_about_summary, Icons.Filled.Info, BadgeHue.AMBER, SettingsSection.APP),
}

/** The headed groups of Settings' first screen, each one card. */
internal enum class SettingsSection(
    @param:StringRes val title: Int,
) {
    ACCOUNT(R.string.settings_section_account),
    WATCHING(R.string.settings_section_watching),
    APP(R.string.settings_section_app),
}

/**
 * Settings' first screen: one row per page, plus captions, a page of its own, in headed cards with a coloured badge
 * beside each page (Melvin, 2026-11-19). Downloads is in the home screen's bar, so it has no row here (Melvin,
 * 2026-11-18). A search box on top finds any setting on any page (`SettingsSearch.kt`). The last card is about Tuned
 * itself: its version and build, and What's new.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpen: (route: Any) -> Unit,
    onSignedOut: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    val tv = isTelevision()
    // On a TV there is no Settings search box (typing); the remote starts on the first page row.
    val start = rememberStartFocus()
    val pages: @Composable (open: (route: Any) -> Unit, shown: SettingsPage?) -> Unit = { open, shown ->
        if (!tv) SettingsSearchField(query) { query = it }
        val words = remember(query, tv) { if (tv) emptyList() else settingsWords(query) }
        if (words.isNotEmpty()) {
            SettingsResults(words, settings, vm, open, onSignedOut)
        } else {
            SettingsSection.entries.forEach { section ->
                SectionHeader(stringResource(section.title))
                SettingsPage.entries.filter { it.section == section }.forEach { page ->
                    PageRow(page, open, selected = page == shown, start = start.takeIf { page == (shown ?: SettingsPage.PLAYLISTS) })
                    // Captions is a screen of its own, so it has its own row after Playback.
                    if (page == SettingsPage.PLAYBACK) CaptionsRow(settings, open)
                }
            }
            SectionHeader(stringResource(R.string.app_name))
            VersionRow()
        }
    }
    if (!ScreenLayout.panes(windowWidthDp())) {
        SettingsScaffold(stringResource(R.string.settings), onBack) { pages(onOpen, null) }
        return
    }
    // A wide screen shows the pages and the page picked side by side, as a tablet's own Settings does (Melvin,
    // 2026-11-19: "Do them all", from the UX audit), rather than one long column stretched across it. A page's row
    // shows it in the right pane; what a page opens (Captions, Playlists, the logs) still opens full screen.
    var shown by rememberSaveable { mutableStateOf(SettingsPage.PLAYLISTS.name) }
    val page = SettingsPage.valueOf(shown)
    val open: (route: Any) -> Unit = { route -> if (route is SettingsPageRoute) shown = route.page else onOpen(route) }
    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings), onBack) }) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            SettingsGroups(
                Modifier
                    .weight(SettingsPanes.LIST)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) { pages(open, page) }
            VerticalDivider()
            key(page) {
                SettingsGroups(
                    Modifier
                        .weight(1 - SettingsPanes.LIST)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                ) {
                    SectionHeader(stringResource(page.title))
                    SettingsPageContent(page, settings, vm, onOpen, onSignedOut)
                }
            }
        }
    }
}

/** The share of a wide screen the list of Settings' pages takes; the page shown takes the rest. */
private object SettingsPanes {
    const val LIST = 0.38f
}

@Composable
private fun CaptionsRow(
    settings: AppSettings,
    onOpen: (route: Any) -> Unit,
) = IconRow(
    Icons.Filled.ClosedCaption,
    BadgeHue.TEAL,
    stringResource(R.string.settings_captions),
    stringResource(if (settings.captions.enabled) R.string.captions_on else R.string.captions_off),
) { onOpen(CaptionSettingsRoute) }

/**
 * The version, the commit the build came from (plain text with no link, Melvin, 2026-11-18; empty when the build had no
 * git), and a What's new button that shows this version's changes from its GitHub release notes. Store builds have no
 * GitHub release to read, so they show the version alone.
 */
@Composable
private fun VersionRow() {
    var notes by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_version_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (BuildConfig.GIT_COMMIT.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_build_commit, BuildConfig.GIT_COMMIT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (BuildConfig.GITHUB_UPDATES) {
            Spacer(Modifier.width(16.dp))
            FilledTonalButton(onClick = { notes = true }) { Text(stringResource(R.string.whats_new)) }
        }
    }
    if (notes) WhatsNewSheet(onDismiss = { notes = false })
}

@Composable
private fun SettingsSearchField(
    text: String,
    onChange: (String) -> Unit,
) {
    TextField(
        value = text,
        onValueChange = onChange,
        placeholder = { Text(stringResource(R.string.settings_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        colors =
            TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        modifier = Modifier.outsideCards().fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Every page's rows that match [words], under the page's name; the rows work as they do on their page. A page whose
 * own name matches is listed as a row that opens it, and Captions (a screen of its own) by its name.
 */
@Composable
private fun SettingsResults(
    words: List<String>,
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (route: Any) -> Unit,
    onSignedOut: () -> Unit,
) {
    // Kept across keystrokes: each row's effect leaves its old query's count and joins the new one in the same frame,
    // so "No settings match" never flickers while typing.
    val counts = remember { SettingsPage.entries.associateWith { mutableIntStateOf(0) } }
    var shown = 0
    val captions = stringResource(R.string.settings_captions)
    if (hasWords(captions, words)) {
        shown++
        CaptionsRow(settings, onOpen)
    }
    SettingsPage.entries.forEach { page ->
        val count = counts.getValue(page)
        val title = stringResource(page.title)
        val pageMatches = hasWords(title, words)
        if (count.intValue > 0 || pageMatches) SectionHeader(title)
        if (pageMatches) {
            shown++
            PageRow(page, onOpen)
        }
        val query = remember(words, page) { SettingsQuery(words, count) }
        CompositionLocalProvider(LocalSettingsQuery provides query) {
            SettingsPageContent(page, settings, vm, onOpen, onSignedOut)
        }
        shown += count.intValue
    }
    if (shown == 0) {
        Text(
            stringResource(R.string.settings_search_none),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.outsideCards().padding(32.dp),
        )
    }
}

/** One page of Settings, by its [SettingsPage] name. */
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpen: (route: Any) -> Unit,
    onSignedOut: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    SettingsScaffold(stringResource(page.title), onBack) {
        SettingsPageContent(page, settings, vm, onOpen, onSignedOut)
    }
}

@Composable
private fun SettingsPageContent(
    page: SettingsPage,
    settings: AppSettings,
    vm: SettingsViewModel,
    onOpen: (route: Any) -> Unit,
    onSignedOut: () -> Unit,
) {
    when (page) {
        SettingsPage.PLAYLISTS -> PlaylistsPage(vm, onOpen, onSignedOut)
        SettingsPage.PROFILES -> ProfilesPage(settings, vm, onOpen)
        SettingsPage.CHANNELS -> ChannelsPage(settings, vm, onOpen)
        SettingsPage.GUIDE -> GuidePage(settings, vm)
        SettingsPage.PLAYBACK -> PlaybackPage(settings, vm)
        SettingsPage.RELIABILITY -> ReliabilityPage(settings, vm, onOpen)
        SettingsPage.APPEARANCE -> AppearancePage(settings, vm, onOpen)
        SettingsPage.ABOUT -> AboutPage(settings, vm, onOpen)
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val start = rememberStartFocus()
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        SettingsGroups(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // A page opened on its own starts the remote on its first row; the search results don't.
            CompositionLocalProvider(LocalRowStartFocus provides start.takeIf { it.enabled }, content)
        }
    }
}

@Composable
private fun PageRow(
    page: SettingsPage,
    onOpen: (route: Any) -> Unit,
    /** The page shown beside the list on a wide screen. */
    selected: Boolean = false,
    /** Set on the row the remote starts on, on a TV. */
    start: StartFocus? = null,
) = IconRow(page.icon, page.hue, stringResource(page.title), stringResource(page.summary), selected, start) {
    onOpen(SettingsPageRoute(page.name))
}

/** A row that opens a page: its coloured badge, its name, and a line saying what's inside. */
@Composable
private fun IconRow(
    icon: ImageVector,
    hue: BadgeHue,
    title: String,
    summary: String,
    selected: Boolean = false,
    start: StartFocus? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .then(if (start != null) Modifier.startFocus(start) else Modifier)
            .focusRing(RowFocusShape, MaterialTheme.colorScheme.primary)
            // The page shown is named as selected for a screen reader, not only by its colour.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, hue)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How long a flow keeps running after the screen stops watching, so turning the device keeps it. */
private const val STOP_MS = 5_000L
