package app.tuned.screenshots

import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import app.tuned.TunedApp
import app.tuned.container
import app.tuned.core.TimeZones
import app.tuned.data.AppSettings
import app.tuned.data.ThemeMode
import app.tuned.ui.AppNav
import app.tuned.ui.browseScreens
import app.tuned.ui.common.LocalTimeZone
import app.tuned.ui.playlistScreens
import app.tuned.ui.settingsScreens
import app.tuned.ui.theme.TunedTheme
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
abstract class Screenshots {
    @get:Rule val compose = createComposeRule()

    protected val app: TunedApp = ApplicationProvider.getApplicationContext()

    @OptIn(DelicateCoilApi::class)
    @Before
    fun setUpSampleData() {
        val engine = FakeImageLoaderEngine.Builder()
        SampleData.art.forEach { (key, image) -> engine.intercept(predicate = { it.toString() == key }, image = image) }
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(app).components { add(engine.build()) }.build())
        runBlocking(Dispatchers.IO) { SampleData.seed(app.container.db) }
    }

    /**
     * Draws [route] (any route of the app's navigation graph) and saves it as `[name].png`, once [wait] is on screen.
     * The picture is saved even when [wait] never shows, so a broken screen can be seen, and then the test fails.
     */
    protected fun shot(
        name: String,
        route: Any,
        wait: String,
        settings: AppSettings = DARK,
    ) {
        runBlocking(Dispatchers.IO) { app.container.settings.update { settings } }
        compose.setContent {
            TunedTheme(mode = settings.theme, pureBlack = settings.pureBlack, accent = settings.accent) {
                CompositionLocalProvider(LocalTimeZone provides TimeZones.resolve(settings.timeZone)) {
                    val nav = rememberNavController()
                    val appNav = AppNav(nav, settings, play = {}, tune = {}, openPerson = {}, openHome = {}, openWelcome = {})
                    NavHost(navController = nav, startDestination = route) {
                        playlistScreens(appNav)
                        browseScreens(appNav)
                        settingsScreens(appNav)
                    }
                }
            }
        }
        val shown =
            runCatching {
                compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(wait, substring = true)).fetchSemanticsNodes().isNotEmpty() }
            }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$name.png")
        shown.getOrThrow()
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
