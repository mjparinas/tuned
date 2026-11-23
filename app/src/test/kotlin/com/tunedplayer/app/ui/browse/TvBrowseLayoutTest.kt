package com.tunedplayer.app.ui.browse

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunedplayer.app.R
import com.tunedplayer.app.TunedApp
import com.tunedplayer.app.container
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.StartTab
import com.tunedplayer.app.data.db.ShowSummary
import com.tunedplayer.app.screenshots.SampleData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Movies and Shows on a TV use the big screen the same way Live TV does: groups down the left, details of the focused
 * title beside the grid (2026-11-22).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvBrowseLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        runBlocking(Dispatchers.IO) {
            SampleData.seed(app.container.db)
            // As the screenshots do: Home reads some of its settings from the store as well as its parameter.
            app.container.settings.update { AppSettings(startTab = StartTab.MOVIES) }
        }
    }

    @Test
    fun moviesGroupsRunDownTheLeftWithTheDetailsBesideTheGrid() {
        compose.setContent {
            HomeScreen(
                settings = AppSettings(startTab = StartTab.MOVIES),
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
        // rememberSaveable can keep a prior test's Live tab; land on Movies so the three-column layout is up.
        compose.onNodeWithText(app.getString(R.string.tab_movies)).performClick()
        // The empty details pane is unique to Movies/Shows on a TV; it appears once past First run.
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasText(app.getString(R.string.tv_browse_pick))).fetchSemanticsNodes().isNotEmpty()
        }
        val all =
            compose
                .onAllNodes(hasText(app.getString(R.string.filter_all)))
                .onFirst()
                .fetchSemanticsNode()
                .boundsInRoot
        val favourites =
            compose
                .onAllNodes(hasText(app.getString(R.string.filter_favourites)))
                .onFirst()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals(true, favourites.top > all.top && favourites.left == all.left)
    }

    /** The details pane for a show: title and episode count from the device, empty until the remote lands on one. */
    @Test
    fun thePosterDetailsShowAShow() {
        val show =
            ShowSummary(
                showKey = "snowline",
                showName = "Snowline",
                logo = null,
                groupName = "Thriller",
                episodes = 3,
                newEpisodes = 0,
            )
        compose.setContent { PosterInfoPane(PosterFocus.Show(show)) }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasText("Snowline", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasText(app.getString(R.string.tv_browse_pick))).assertCountEquals(0)
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
