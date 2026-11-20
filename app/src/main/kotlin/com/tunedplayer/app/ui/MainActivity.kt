package com.tunedplayer.app.ui

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tunedplayer.app.R
import com.tunedplayer.app.container
import com.tunedplayer.app.core.TimeZones
import com.tunedplayer.app.core.TvHandoff
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.CastMember
import com.tunedplayer.app.diagnostics.launchSafely
import com.tunedplayer.app.player.PlayerActivity
import com.tunedplayer.app.ui.common.LocalTimeZone
import com.tunedplayer.app.ui.common.TvSafeArea
import com.tunedplayer.app.ui.common.WithOfflineBanner
import com.tunedplayer.app.ui.theme.TunedTheme
import com.tunedplayer.app.updates.UpdateNotice
import com.tunedplayer.app.widget.AppShortcuts
import com.tunedplayer.app.widget.Widgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var updates: AppUpdates

    /** A search from the Assistant, a TV remote's search button or the system search, waiting to be opened. */
    private val incomingSearch = MutableStateFlow<String?>(null)

    /** A screen an app icon shortcut asked for ([AppShortcuts]), waiting to be opened. */
    private val incomingScreen = MutableStateFlow<String?>(null)

    /** A TV's sign-in QR code, opened on this phone, waiting to be answered (docs/decisions/onboarding.md). */
    private val incomingTv = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        updates = AppUpdates(this)
        enableEdgeToEdge()
        val settings = container.settings.settings.stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        // Hold the splash only until settings load (a few ms), so the first frame already has the right theme.
        splash.setKeepOnScreenCondition { settings.value == null }
        val signedIn = container.credentials.load() != null
        incomingSearch.value = searchQuery(intent)
        incomingScreen.value = shortcutScreen(intent)
        incomingTv.value = tvLink(intent)
        // A card on the Android TV home screen ([com.tunedplayer.app.work.TvHome]): play it, with the lists underneath.
        val request = if (savedInstanceState == null && signedIn) playRequest(intent) else null
        if (request != null) PlayerActivity.start(this, request)
        // Like a cable box: a fresh launch from the home screen goes straight into the last channel (a setting, off by
        // default). The lists are underneath, from the cache, for when the player closes.
        val fresh = savedInstanceState == null && signedIn && intent?.action == Intent.ACTION_MAIN
        if (request == null && fresh && incomingSearch.value == null && incomingScreen.value == null) {
            lifecycleScope.launchSafely("Main") {
                val prefs = container.settings.settings.first()
                if (!prefs.startsOnLastChannel()) return@launchSafely
                val items = container.db.items()
                val channel = items.lastWatchedChannel() ?: return@launchSafely
                PlayerActivity.start(this@MainActivity, channel.id)
            }
        }

        setContent {
            val s by settings.collectAsStateWithLifecycle()
            val current = s ?: return@setContent
            TunedTheme(
                mode = current.theme,
                dynamicColour = current.dynamicColour,
                pureBlack = current.pureBlack,
                accent = current.accent,
            ) {
                CompositionLocalProvider(LocalTimeZone provides TimeZones.resolve(current.timeZone)) {
                    TvSafeArea { Navigation(current, signedIn) }
                }
            }
        }
    }

    @Composable
    private fun Navigation(
        current: AppSettings,
        signedIn: Boolean,
    ) {
        val nav = rememberNavController()
        val play: (Long) -> Unit = { id -> playIfReachable(id) }
        val tune: (String) -> Unit = { query ->
            if (offline()) offlineNotice() else PlayerActivity.tune(this@MainActivity, query)
        }
        val online by container.connectivity.online.collectAsStateWithLifecycle()
        val entry by nav.currentBackStackEntryAsState()
        val here = entry?.destination
        // A way to the saved films and episodes, except where it can't help (adding a playlist, or already there).
        val addingPlaylist = here != null && (here.hasRoute<WelcomeRoute>() || here.hasRoute<AddPlaylistRoute>())
        val toDownloads =
            if (here == null || addingPlaylist || here.hasRoute<DownloadsRoute>()) {
                null
            } else {
                { nav.navigate(DownloadsRoute) { launchSingleTop = true } }
            }
        WithOfflineBanner(offline = !online, onOpenDownloads = toDownloads) {
            Routes(nav, current, signedIn, play, tune)
        }
    }

    @Composable
    private fun Routes(
        nav: NavHostController,
        current: AppSettings,
        signedIn: Boolean,
        play: (Long) -> Unit,
        tune: (String) -> Unit,
    ) {
        val pending by incomingSearch.collectAsStateWithLifecycle()
        LaunchedEffect(pending) {
            val query = pending ?: return@LaunchedEffect
            if (container.credentials.load() != null) nav.navigate(SearchRoute(query)) { launchSingleTop = true }
            incomingSearch.value = null
        }
        val screen by incomingScreen.collectAsStateWithLifecycle()
        LaunchedEffect(screen) {
            val name = screen ?: return@LaunchedEffect
            if (container.credentials.load() != null) {
                when (name) {
                    AppShortcuts.SEARCH -> nav.navigate(SearchRoute()) { launchSingleTop = true }
                    AppShortcuts.EVENTS -> nav.navigate(EventsRoute) { launchSingleTop = true }
                }
            }
            incomingScreen.value = null
        }
        val tvAsk by incomingTv.collectAsStateWithLifecycle()
        LaunchedEffect(tvAsk) {
            val link = tvAsk ?: return@LaunchedEffect
            nav.navigate(SendToTvRoute(link)) { launchSingleTop = true }
            incomingTv.value = null
        }
        val openPerson: (CastMember) -> Unit = { person -> nav.navigate(PersonRoute(person.id ?: 0, person.name)) }
        val openHome: () -> Unit = { nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } }
        val openWelcome: () -> Unit = { nav.navigate(WelcomeRoute) { popUpTo(0) { inclusive = true } } }
        // Decided once: a later change to the profiles mustn't swap the start of a back stack already built.
        val start: Any =
            remember {
                when {
                    !signedIn -> WelcomeRoute
                    current.asksProfileAtStart() -> ProfilesRoute(atStart = true)
                    else -> HomeRoute
                }
            }
        val app = AppNav(nav, current, play, tune, openPerson, openHome, openWelcome)
        NavHost(navController = nav, startDestination = start) {
            // Each feature adds its own screens, in its own file, so two features never edit the same lines here.
            playlistScreens(app)
            browseScreens(app)
            settingsScreens(app)
        }
    }

    /**
     * Offline, only what's saved on the device can play: a channel or a film that isn't downloaded says so instead of
     * opening a player that can only fail. Offline means both the settled state (the banner) and Android's answer now
     * agree, so a moment between two networks never stops a tap.
     */
    private fun playIfReachable(id: Long) {
        if (!offline()) {
            PlayerActivity.start(this, id)
            return
        }
        lifecycleScope.launchSafely("Main") {
            if (container.offline.files(listOf(id)).isNotEmpty()) PlayerActivity.start(this@MainActivity, id) else offlineNotice()
        }
    }

    private fun offline(): Boolean = !container.connectivity.online.value && !container.connectivity.onlineNow()

    private fun offlineNotice() {
        Toast.makeText(this, R.string.offline_cant_play, Toast.LENGTH_LONG).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        searchQuery(intent)?.let { incomingSearch.value = it }
        shortcutScreen(intent)?.let { incomingScreen.value = it }
        tvLink(intent)?.let { incomingTv.value = it }
        playRequest(intent)?.let { PlayerActivity.start(this, it) }
    }

    private fun playRequest(intent: Intent?): Long? {
        if (intent?.action != ACTION_PLAY) return null
        return intent.getLongExtra(EXTRA_PLAY_ID, 0).takeIf { it != 0L }
    }

    private fun tvLink(intent: Intent?): String? = intent
        ?.takeIf { it.action == Intent.ACTION_VIEW }
        ?.dataString
        ?.takeIf { TvHandoff.parseLink(it) != null }

    private fun shortcutScreen(intent: Intent?): String? =
        if (intent?.action == AppShortcuts.ACTION_OPEN) intent.getStringExtra(AppShortcuts.EXTRA_SCREEN) else null

    /** "Search for UFC on Tuned" (Assistant), the TV remote's search, or the system search box. */
    private fun searchQuery(intent: Intent?): String? {
        val action = intent?.action ?: return null
        if (action != Intent.ACTION_SEARCH && action != ASSISTANT_SEARCH) return null
        return intent.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun onResume() {
        super.onResume()
        // Every time Tuned comes to the front, not only on a cold start: a copy left open for days still updates.
        updates.check()
        updates.resume()
        UpdateNotice.clear(this)
        // The app icon's shortcuts follow the last channel watched and the setting.
        lifecycleScope.launchSafely("Shortcuts") { withContext(Dispatchers.IO) { AppShortcuts.update(this@MainActivity) } }
    }

    override fun onStop() {
        super.onStop()
        // Leaving Tuned: the home screen widgets catch up with favourites, the theme and the profile picked here.
        container.appScope.launchSafely("Widgets") { Widgets.refresh(applicationContext) }
    }

    override fun onDestroy() {
        updates.stop()
        super.onDestroy()
    }
}

private const val ASSISTANT_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"

/** Plays item [EXTRA_PLAY_ID]: what the Android TV home screen's cards open. */
const val ACTION_PLAY = "com.tunedplayer.app.action.PLAY"
const val EXTRA_PLAY_ID = "play_id"
