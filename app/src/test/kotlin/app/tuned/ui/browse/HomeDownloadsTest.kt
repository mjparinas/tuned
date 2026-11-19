package app.tuned.ui.browse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
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

/** Downloads opens as a section of Home, so the bar at the bottom stays and its tabs still work (Melvin, 2026-11-19). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeDownloadsTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Home asks WorkManager what is scheduled; the app turns its start-up initializer off.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun tab(id: Int) = compose.onNode(hasText(app.getString(id)) and isSelectable())

    @Test
    fun theBarStaysOnDownloadsAndATabLeavesIt() {
        compose.setContent {
            HomeScreen(
                settings = AppSettings(),
                onPlay = {},
                onOpenShow = { _, _ -> },
                onOpenGuide = {},
                onOpenMovie = {},
                onOpenPerson = {},
                onOpenSettings = {},
                onOpenProfiles = {},
                onOpenSearch = {},
                onOpenEvents = {},
                onTune = {},
                onOpenPlaylists = {},
            )
        }
        tab(R.string.downloads).performClick()
        compose.waitForIdle()
        tab(R.string.downloads).assertIsSelected()
        tab(R.string.tab_live).assertIsDisplayed().assertIsNotSelected()

        tab(R.string.tab_movies).performClick()
        compose.waitForIdle()
        tab(R.string.tab_movies).assertIsSelected()
        tab(R.string.downloads).assertIsNotSelected()
    }
}
