package app.tuned.ui.settings

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** On a wide screen Settings shows its pages and the page picked side by side, and a page's row opens it beside. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-xhdpi")
class SettingsPanesTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test
    fun aPageOpensBesideTheList() {
        var navigated = 0
        compose.setContent {
            SettingsScreen(settings = AppSettings(), onBack = {}, onOpen = { navigated++ }, onSignedOut = {})
        }
        // The pane's heading names the page shown, Playlists at first (headings are read by their words as written).
        val appearance = app.getString(R.string.settings_appearance)
        compose.onAllNodes(hasContentDescription(app.getString(R.string.settings_page_playlists))).onFirst().assertExists()
        assertEquals(0, compose.onAllNodes(hasContentDescription(appearance)).fetchSemanticsNodes().size)

        // Appearance is near the foot of the list, below the screen, so it is scrolled to first.
        compose
            .onAllNodes(hasText(appearance))
            .onFirst()
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasContentDescription(appearance)).onFirst().assertExists()
        assertEquals(0, navigated)
    }
}
