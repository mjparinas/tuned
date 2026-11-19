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
 * On tablets and TV, Settings sits on the rail under Downloads and leaves the menu by search; a TV has no menu at all
 * (Melvin, 2026-11-19).
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
    fun settingsIsOnTheRailAndNotInTheMenu() {
        var opened = 0
        home { opened++ }
        val settings = app.getString(R.string.settings)
        compose.onNode(hasText(settings) and isSelectable()).performClick()
        assertEquals(1, opened)

        compose.onNodeWithContentDescription(app.getString(R.string.home_menu)).performClick()
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasText(settings)).fetchSemanticsNodes().size)
    }

    /** A TV has no menu at all: search, Settings and the guide toggle are each one press away. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    fun aTvHasNoMenu() {
        var opened = 0
        home { opened++ }
        assertEquals(0, compose.onAllNodes(hasContentDescription(app.getString(R.string.home_menu))).fetchSemanticsNodes().size)
        compose.onNode(hasText(app.getString(R.string.rail_search)) and isSelectable()).assertExists()
        compose.onNode(hasText(app.getString(R.string.settings)) and isSelectable()).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(app.getString(R.string.grid_show)).assertExists()
    }
}
