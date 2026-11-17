package app.tuned.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.tuned.container
import app.tuned.player.PlayerActivity
import app.tuned.ui.browse.HomeScreen
import app.tuned.ui.guide.GuideScreen
import app.tuned.ui.login.LoginScreen
import app.tuned.ui.settings.CaptionSettingsScreen
import app.tuned.ui.settings.RegionSettingsScreen
import app.tuned.ui.settings.SettingsScreen
import app.tuned.ui.shows.ShowScreen
import app.tuned.ui.theme.TunedTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {
    private lateinit var updates: AppUpdates

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        updates = AppUpdates(this)
        if (savedInstanceState == null) updates.check()
        enableEdgeToEdge()
        val settings = container.settings.settings.stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        // Hold the splash only until settings load (a few ms), so the first frame already has the right theme.
        splash.setKeepOnScreenCondition { settings.value == null }
        val signedIn = container.credentials.load() != null

        setContent {
            val s by settings.collectAsStateWithLifecycle()
            val current = s ?: return@setContent
            TunedTheme(mode = current.theme, dynamicColour = current.dynamicColour, pureBlack = current.pureBlack) {
                val nav = rememberNavController()
                val play: (Long) -> Unit = { id -> PlayerActivity.start(this, id) }
                NavHost(navController = nav, startDestination = if (signedIn) HomeRoute else LoginRoute) {
                    composable<LoginRoute> {
                        LoginScreen(onSignedIn = {
                            nav.navigate(HomeRoute) { popUpTo<LoginRoute> { inclusive = true } }
                        })
                    }
                    composable<HomeRoute> {
                        HomeScreen(
                            settings = current,
                            onPlay = play,
                            onOpenShow = { key, title -> nav.navigate(ShowRoute(key, title)) },
                            onOpenGuide = { id -> nav.navigate(GuideRoute(id)) },
                            onOpenSettings = { nav.navigate(SettingsRoute) },
                            onTune = { query -> PlayerActivity.tune(this@MainActivity, query) },
                        )
                    }
                    composable<ShowRoute> { entry ->
                        val route = entry.toRoute<ShowRoute>()
                        ShowScreen(showKey = route.showKey, title = route.title, onPlay = play, onBack = { nav.popBackStack() })
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
                            onSignedOut = {
                                nav.navigate(LoginRoute) { popUpTo(0) { inclusive = true } }
                            },
                        )
                    }
                    composable<CaptionSettingsRoute> {
                        CaptionSettingsScreen(settings = current, onBack = { nav.popBackStack() })
                    }
                    composable<RegionSettingsRoute> {
                        RegionSettingsScreen(settings = current, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updates.resume()
    }

    override fun onDestroy() {
        updates.stop()
        super.onDestroy()
    }
}
