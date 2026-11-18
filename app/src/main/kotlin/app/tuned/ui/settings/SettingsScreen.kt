package app.tuned.ui.settings

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tuned.BuildConfig
import app.tuned.R
import app.tuned.container
import app.tuned.data.AppSettings
import app.tuned.diagnostics.launchSafely
import app.tuned.ui.CaptionSettingsRoute
import app.tuned.ui.SettingsPageRoute
import app.tuned.ui.common.BackTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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

    fun clearHistory() {
        viewModelScope.launchSafely("Settings") { container.db.library().clearHistory() }
    }

    /** Signing out forgets the login, the cached playlist and guide, and stops background refreshes. */
    fun signOut(onDone: () -> Unit) {
        viewModelScope.launchSafely("Settings") {
            container.session.signOut()
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
) {
    PLAYLISTS(R.string.settings_page_playlists, R.string.settings_page_playlists_summary, Icons.AutoMirrored.Filled.PlaylistPlay),
    PROFILES(R.string.settings_page_profiles, R.string.settings_page_profiles_summary, Icons.Filled.People),
    CHANNELS(R.string.settings_page_channels, R.string.settings_page_channels_summary, Icons.Filled.Tv),
    GUIDE(R.string.settings_page_guide, R.string.settings_page_guide_summary, Icons.Filled.CalendarMonth),
    PLAYBACK(R.string.settings_playback, R.string.settings_page_playback_summary, Icons.Filled.PlayCircle),
    RELIABILITY(R.string.settings_page_reliability, R.string.settings_page_reliability_summary, Icons.Filled.Autorenew),
    APPEARANCE(R.string.settings_appearance, R.string.settings_page_appearance_summary, Icons.Filled.Palette),
    ABOUT(R.string.settings_page_about, R.string.settings_page_about_summary, Icons.Filled.Info),
}

/**
 * Settings' first screen: one row per page, plus captions, a page of its own. Downloads is in the home screen's bar,
 * so it has no row here (Melvin, 2026-11-18). A search box on top finds any setting on any page (`SettingsSearch.kt`).
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onOpen: (route: Any) -> Unit,
    onSignedOut: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    SettingsScaffold(stringResource(R.string.settings), onBack) {
        var query by rememberSaveable { mutableStateOf("") }
        SettingsSearchField(query) { query = it }
        val words = remember(query) { settingsWords(query) }
        if (words.isNotEmpty()) {
            SettingsResults(words, settings, vm, onOpen, onSignedOut)
            return@SettingsScaffold
        }
        val pages = SettingsPage.entries
        pages.take(pages.indexOf(SettingsPage.PLAYBACK) + 1).forEach { PageRow(it, onOpen) }
        IconRow(
            Icons.Filled.ClosedCaption,
            stringResource(R.string.settings_captions),
            stringResource(if (settings.captions.enabled) R.string.captions_on else R.string.captions_off),
        ) { onOpen(CaptionSettingsRoute) }
        pages.drop(pages.indexOf(SettingsPage.PLAYBACK) + 1).forEach { PageRow(it, onOpen) }
        HorizontalDivider(Modifier.padding(top = 16.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The commit this build came from, as plain text with no link (Melvin, 2026-11-18). Empty when the build had no git.
            if (BuildConfig.GIT_COMMIT.isNotEmpty()) {
                Text(
                    stringResource(R.string.settings_build_commit, BuildConfig.GIT_COMMIT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
        IconRow(
            Icons.Filled.ClosedCaption,
            captions,
            stringResource(if (settings.captions.enabled) R.string.captions_on else R.string.captions_off),
        ) { onOpen(CaptionSettingsRoute) }
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
            modifier = Modifier.padding(16.dp),
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
        SettingsPage.APPEARANCE -> AppearancePage(settings, vm)
        SettingsPage.ABOUT -> AboutPage(settings, vm, onOpen)
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(topBar = { BackTopBar(title, onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) { content() }
    }
}

@Composable
private fun PageRow(
    page: SettingsPage,
    onOpen: (route: Any) -> Unit,
) = IconRow(page.icon, stringResource(page.title), stringResource(page.summary)) { onOpen(SettingsPageRoute(page.name)) }

@Composable
private fun IconRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
