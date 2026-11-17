package app.tuned.ui

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.tuned.R
import app.tuned.container
import app.tuned.data.AppSettings
import app.tuned.diagnostics.launchSafely
import app.tuned.player.PlayerActivity
import app.tuned.ui.browse.HomeScreen
import app.tuned.ui.common.WithOfflineBanner
import app.tuned.ui.details.MovieScreen
import app.tuned.ui.guide.GuideScreen
import app.tuned.ui.login.LoginScreen
import app.tuned.ui.offline.DownloadsScreen
import app.tuned.ui.search.SearchScreen
import app.tuned.ui.settings.CaptionSettingsScreen
import app.tuned.ui.settings.ChannelHealthScreen
import app.tuned.ui.settings.DeveloperLogScreen
import app.tuned.ui.settings.FavouriteOrderScreen
import app.tuned.ui.settings.HiddenGroupsScreen
import app.tuned.ui.settings.RegionSettingsScreen
import app.tuned.ui.settings.SettingsScreen
import app.tuned.ui.shows.ShowScreen
import app.tuned.ui.theme.TunedTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {
    private lateinit var updates: AppUpdates

    /** A search from the Assistant, a TV remote's search button or the system search, waiting to be opened. */
    private val incomingSearch = MutableStateFlow<String?>(null)

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

        setContent {
            val s by settings.collectAsStateWithLifecycle()
            val current = s ?: return@setContent
            TunedTheme(
                mode = current.theme,
                dynamicColour = current.dynamicColour,
                pureBlack = current.pureBlack,
                accent = current.accent,
            ) {
                Navigation(current, signedIn)
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
        // A way to the saved films and episodes, except where it can't help (signed out, or already there).
        val toDownloads =
            if (here == null || here.hasRoute<LoginRoute>() || here.hasRoute<DownloadsRoute>()) {
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
        NavHost(navController = nav, startDestination = if (signedIn) HomeRoute else LoginRoute) {
            composable<LoginRoute> {
                // From sign-in or from Settings › Playlists › Add: either way the new playlist opens on its own.
                LoginScreen(onSignedIn = {
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                })
            }
            composable<HomeRoute> {
                HomeScreen(
                    settings = current,
                    onPlay = play,
                    onOpenShow = { key, title -> nav.navigate(ShowRoute(key, title)) },
                    onOpenGuide = { id -> nav.navigate(GuideRoute(id)) },
                    onOpenMovie = { id -> nav.navigate(MovieRoute(id)) },
                    onOpenSettings = { nav.navigate(SettingsRoute) },
                    onOpenSearch = { nav.navigate(SearchRoute()) },
                    onOpenDownloads = { nav.navigate(DownloadsRoute) },
                    onTune = tune,
                    onSignedOut = { nav.navigate(LoginRoute) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable<SearchRoute> { entry ->
                SearchScreen(
                    initialQuery = entry.toRoute<SearchRoute>().query,
                    onBack = { nav.popBackStack() },
                    onPlay = play,
                    onOpenShow = { key, title -> nav.navigate(ShowRoute(key, title)) },
                    onOpenMovie = { id -> nav.navigate(MovieRoute(id)) },
                    onTune = tune,
                )
            }
            composable<ShowRoute> { entry ->
                val route = entry.toRoute<ShowRoute>()
                ShowScreen(showKey = route.showKey, title = route.title, onPlay = play, onBack = { nav.popBackStack() })
            }
            composable<MovieRoute> { entry ->
                MovieScreen(itemId = entry.toRoute<MovieRoute>().itemId, onPlay = play, onBack = { nav.popBackStack() })
            }
            composable<GuideRoute> { entry ->
                GuideScreen(itemId = entry.toRoute<GuideRoute>().itemId, onPlay = play, onBack = { nav.popBackStack() })
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    settings = current,
                    onBack = { nav.popBackStack() },
                    onOpenCaptions = { nav.navigate(CaptionSettingsRoute) },
                    onOpenRegions = { nav.navigate(RegionSettingsRoute) },
                    onOpenHiddenGroups = { nav.navigate(HiddenGroupsRoute) },
                    onOpenChannelHealth = { nav.navigate(ChannelHealthRoute) },
                    onOpenFavouriteOrder = { nav.navigate(FavouriteOrderRoute) },
                    onOpenDownloads = { nav.navigate(DownloadsRoute) },
                    onOpenLogs = { nav.navigate(DeveloperLogRoute) },
                    onSignedOut = {
                        nav.navigate(LoginRoute) { popUpTo(0) { inclusive = true } }
                    },
                    onAddPlaylist = { nav.navigate(LoginRoute) },
                    onSwitchedPlaylist = { nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable<DeveloperLogRoute> { DeveloperLogScreen(onBack = { nav.popBackStack() }) }
            composable<DownloadsRoute> {
                DownloadsScreen(onPlay = play, onBack = { nav.popBackStack() })
            }
            composable<CaptionSettingsRoute> {
                CaptionSettingsScreen(settings = current, onBack = { nav.popBackStack() })
            }
            composable<FavouriteOrderRoute> { FavouriteOrderScreen(onBack = { nav.popBackStack() }) }
            composable<RegionSettingsRoute> {
                RegionSettingsScreen(settings = current, onBack = { nav.popBackStack() })
            }
            composable<HiddenGroupsRoute> {
                HiddenGroupsScreen(settings = current, onBack = { nav.popBackStack() })
            }
            composable<ChannelHealthRoute> {
                ChannelHealthScreen(settings = current, onBack = { nav.popBackStack() })
            }
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
    }

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
    }

    override fun onDestroy() {
        updates.stop()
        super.onDestroy()
    }
}

private const val ASSISTANT_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"
