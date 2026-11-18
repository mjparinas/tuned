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
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.tuned.R
import app.tuned.container
import app.tuned.data.AppSettings
import app.tuned.data.CastMember
import app.tuned.diagnostics.launchSafely
import app.tuned.player.PlayerActivity
import app.tuned.ui.common.WithOfflineBanner
import app.tuned.ui.theme.TunedTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
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
        // A card on the Android TV home screen ([app.tuned.work.TvHome]): play it, with the lists underneath.
        val request = if (savedInstanceState == null && signedIn) playRequest(intent) else null
        if (request != null) PlayerActivity.start(this, request)
        // Like a cable box: a fresh launch from the home screen goes straight into the last channel (a setting, off by
        // default). The lists are underneath, from the cache, for when the player closes.
        val fresh = savedInstanceState == null && signedIn && intent?.action == Intent.ACTION_MAIN
        if (request == null && fresh && incomingSearch.value == null) {
            lifecycleScope.launchSafely("Main") {
                val prefs = container.settings.settings.first()
                if (!prefs.startOnLastChannel) return@launchSafely
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
        val openPerson: (CastMember) -> Unit = { person -> nav.navigate(PersonRoute(person.id ?: 0, person.name)) }
        val openHome: () -> Unit = { nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } }
        val openWelcome: () -> Unit = { nav.navigate(WelcomeRoute) { popUpTo(0) { inclusive = true } } }
        // Decided once: a later change to the profiles mustn't swap the start of a back stack already built.
        val start: Any =
            remember {
                when {
                    !signedIn -> WelcomeRoute
                    current.profileCount > 1 && current.askProfileAtStart -> ProfilesRoute(atStart = true)
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
        playRequest(intent)?.let { PlayerActivity.start(this, it) }
    }

    private fun playRequest(intent: Intent?): Long? {
        if (intent?.action != ACTION_PLAY) return null
        return intent.getLongExtra(EXTRA_PLAY_ID, 0).takeIf { it != 0L }
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

/** Plays item [EXTRA_PLAY_ID]: what the Android TV home screen's cards open. */
const val ACTION_PLAY = "app.tuned.action.PLAY"
const val EXTRA_PLAY_ID = "play_id"
