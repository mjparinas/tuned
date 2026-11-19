package app.tuned.ui.browse

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.tuned.R
import app.tuned.TunedApp
import app.tuned.data.AppSettings
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * No menu beside search on any device (Melvin, 2026-11-19): Settings is on the rail on tablets and TV and in the bottom
 * bar on phones, and search everything is the button at the start of the search box (on a TV, on the rail).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class HomeRailSettingsTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun home(onOpenSettings: () -> Unit = {}) {
        compose.setContent {
            HomeScreen(
                settings = AppSettings(),
                onPlay = {},
                onOpenShow = { _, _ -> },
                onOpenGuide = {},
                onOpenMovie = {},
                onOpenPerson = {},
                onOpenSettings = onOpenSettings,
                onOpenProfiles = {},
                onOpenSearch = {},
                onOpenEvents = {},
                onTune = {},
                onOpenPlaylists = {},
            )
        }
    }

    @Test
    fun settingsIsOnTheRailOnceAndThereIsNoMenu() {
        var opened = 0
        home { opened++ }
        val settings = app.getString(R.string.settings)
        compose.onNode(hasText(settings) and isSelectable()).performClick()
        assertEquals(1, opened)
        assertEquals(1, compose.onAllNodes(hasText(settings)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription(app.getString(R.string.a11y_more_options))).fetchSemanticsNodes().size)
    }

    /** A phone has no menu either (Melvin, 2026-11-19: "avoid 3 dot drop downs"): Settings is in the bottom bar. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun aPhoneHasSettingsInTheBarAndSearchEverythingInTheBox() {
        var opened = 0
        var searched = 0
        compose.setContent {
            HomeScreen(
                settings = AppSettings(),
                onPlay = {},
                onOpenShow = { _, _ -> },
                onOpenGuide = {},
                onOpenMovie = {},
                onOpenPerson = {},
                onOpenSettings = { opened++ },
                onOpenProfiles = {},
                onOpenSearch = { searched++ },
                onOpenEvents = {},
                onTune = {},
                onOpenPlaylists = {},
            )
        }
        compose.onNode(hasText(app.getString(R.string.settings)) and isSelectable()).performClick()
        assertEquals(1, opened)
        compose.onNodeWithContentDescription(app.getString(R.string.search_everything)).performClick()
        assertEquals(1, searched)
        compose.onNodeWithContentDescription(app.getString(R.string.grid_show)).assertExists()
    }

    /** A TV has no menu at all: search, Settings and the guide toggle are each one press away. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun aTvHasNoMenu() {
        var opened = 0
        home { opened++ }
        compose.onNode(hasText(app.getString(R.string.rail_search)) and isSelectable()).assertExists()
        compose.onNode(hasText(app.getString(R.string.settings)) and isSelectable()).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(app.getString(R.string.grid_show)).assertExists()
    }
}
