package com.tunedplayer.app.screenshots

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.tunedplayer.app.TunedApp
import com.tunedplayer.app.container
import com.tunedplayer.app.core.TimeZones
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.BrowseFilterStore
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.AppNav
import com.tunedplayer.app.ui.browseScreens
import com.tunedplayer.app.ui.common.LocalTimeZone
import com.tunedplayer.app.ui.common.TvSafeArea
import com.tunedplayer.app.ui.playlistScreens
import com.tunedplayer.app.ui.settingsScreens
import com.tunedplayer.app.ui.theme.TunedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the app's screens, drawn on the JVM by Robolectric with made-up sample data ([SampleData]): no build to
 * install, no emulator, no real playlist, login or stream address. Each picture is a real route of the app's own
 * navigation graph, so a screen looks exactly as it does in the app and a change to a screen's parameters never
 * breaks it.
 *
 * Run with `./gradlew :app:testGithubDebugUnitTest -Pscreenshots`; the PNGs land in `app/build/outputs/roborazzi`.
 * Ordinary test runs skip these. On GitHub, the Screenshots workflow runs them for a branch and keeps the pictures on
 * the `screenshots/<branch>` branch (docs/decisions/screenshots.md).
 *
 * Each area has its own file ([BrowseScreenshots], [SettingsScreenshots], [PlaylistScreenshots]); a new screen adds
 * one line to its area's file.
 */
@OptIn(ExperimentalRoborazziApi::class) // captureScreenRoboImage, for dialogs, which draw in their own window
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
abstract class Screenshots {
    @get:Rule val compose = createComposeRule()

    protected val app: TunedApp = ApplicationProvider.getApplicationContext()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUpSampleData() {
        // The app turns WorkManager's start-up initializer off, and the Home, guide and Live Events screens ask it what
        // is scheduled, so these screens need the test one.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val engine = FakeImageLoaderEngine.Builder()
        SampleData.art.forEach { (key, image) -> engine.intercept(predicate = { it.toString() == key }, image = image) }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(app).components { add(engine.build()) }.build())
        runBlocking(Dispatchers.IO) { SampleData.seed(app.container.db) }
        // Filters chosen on a browse tab are kept on the device (BrowseFilterStore), and its DataStore outlives a test,
        // so one shot's chosen filters (liveTvFiltersChosen) would empty Live TV in every shot after it.
        runBlocking { BrowseFilterStore(app).clear() }
    }

    /**
     * Draws [route] (any route of the app's navigation graph) and saves it as `[name].png`, once [wait] is on screen.
     * The picture is saved even when [wait] never shows, so a broken screen can be seen, and then the test fails.
     * [then] runs once [wait] shows, for a picture of what a tap opens (a dialog, say).
     */
    protected fun shot(
        name: String,
        route: Any,
        wait: String,
        settings: AppSettings = DARK,
        then: () -> Unit = {},
        /** Drawn over the screen, such as a dialog; the picture then takes in every window, not just the screen's. */
        overlay: (@Composable () -> Unit)? = null,
    ) {
        runBlocking(Dispatchers.IO) { app.container.settings.update { settings } }
        compose.setContent {
            TunedTheme(mode = settings.theme, pureBlack = settings.pureBlack, accent = settings.accent) {
                CompositionLocalProvider(LocalTimeZone provides TimeZones.resolve(settings.timeZone)) {
                    TvSafeArea {
                        val nav = rememberNavController()
                        val appNav = AppNav(nav, settings, play = {}, tune = {}, openPerson = {}, openHome = {}, openWelcome = {})
                        NavHost(navController = nav, startDestination = route) {
                            playlistScreens(appNav)
                            browseScreens(appNav)
                            settingsScreens(appNav)
                        }
                    }
                    overlay?.invoke()
                }
            }
        }
        val matcher = hasText(wait, substring = true) or hasContentDescription(wait, substring = true)
        val shown =
            runCatching {
                compose.waitUntil(WAIT_MS) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
            }
        if (shown.isSuccess) then()
        compose.waitForIdle()
        if (overlay == null) compose.onRoot().captureRoboImage("$name.png") else captureScreenRoboImage("$name.png")
        shown.getOrThrow()
    }

    /**
     * Draws one part of a screen on its own, for a state sample data can't reach (a refresh that is part way through),
     * and saves it as `[name].png` once [wait] is on screen.
     */
    protected fun part(
        name: String,
        wait: String,
        settings: AppSettings = DARK,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            TunedTheme(mode = settings.theme, pureBlack = settings.pureBlack, accent = settings.accent) {
                TvSafeArea { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
        compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(wait, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$name.png")
    }

    /** A string from the app's resources, for waiting on a screen's title. */
    protected fun text(
        @StringRes id: Int,
    ): String = app.getString(id)

    companion object {
        val DARK = AppSettings(theme = ThemeMode.DARK)
        val LIGHT = AppSettings(theme = ThemeMode.LIGHT)

        private const val WAIT_MS = 15_000L
    }
}

/** A typical phone: 411 by 891 dp at 3x, 1233 by 2673 pixels. */
const val PHONE = "w411dp-h891dp-xxhdpi"

/** A 10-inch tablet held sideways, where the navigation rail and two panes show (docs/decisions/large-screens.md). */
const val TABLET = "w1280dp-h800dp-land-xhdpi"

/** A 1080p Android TV (960 by 540 dp), drawn inside its overscan margin (docs/decisions/tv.md). */
const val TV = "w960dp-h540dp-land-television-xhdpi"

/** Melvin's TV (an Nvidia Shield), which lays out at about 853 by 480 dp, shorter than [TV]. */
const val SMALL_TV = "w853dp-h480dp-land-television-xhdpi"
