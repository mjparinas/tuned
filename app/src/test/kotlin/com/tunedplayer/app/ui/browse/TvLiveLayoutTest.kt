package com.tunedplayer.app.ui.browse

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunedplayer.app.R
import com.tunedplayer.app.TunedApp
import com.tunedplayer.app.container
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.db.ChannelWithNow
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
 * Live TV on a TV keeps the groups in a horizontal row in both list and guide views (Melvin, 2026-11-22), and shows
 * what the focused channel is playing beside the list. The remote starts on the first channel, so its details show at
 * once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // text measured as on a device, as the screenshots are, so rows lay out
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvLiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        runBlocking(Dispatchers.IO) {
            SampleData.seed(app.container.db)
            // As the screenshots do: Home reads some of its settings from the store as well as its parameter.
            app.container.settings.update { AppSettings() }
        }
    }

    @Test
    fun groupsRunAcrossTheTopWithTheDetailsBesideTheList() {
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
        compose.waitUntil(WAIT_MS) {
            compose
                .onAllNodes(
                    hasText("Maple News 24", substring = true) or hasContentDescription("Maple News 24", substring = true),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        // The same horizontal chip bar is used by the TV's list and guide views.
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
        assertEquals(true, favourites.left > all.left && favourites.top == all.top)
        // The details pane sits beside the list, waiting for the remote to land on a channel.
        compose.onAllNodes(hasText(app.getString(R.string.tv_live_pick))).onFirst().assertExists()
    }

    /** The pane shows the channel's programme on now from the guide on the device, and what's next. */
    @Test
    fun theDetailsShowWhatIsOnNow() {
        val channel =
            ChannelWithNow(
                id = 1,
                name = "Maple News 24",
                logo = null,
                groupName = "CA | News",
                tvgId = "ch0",
                nowTitle = null,
                nowStart = null,
                nowStop = null,
                favourite = false,
                number = 1,
            )
        compose.setContent { ChannelInfoPane(channel) }
        compose.waitUntil(WAIT_MS) {
            compose.onAllNodes(hasText(app.getString(R.string.tv_live_on_now), substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasText(app.getString(R.string.tv_live_pick))).assertCountEquals(0)
    }

    private companion object {
        const val WAIT_MS = 15_000L
    }
}
