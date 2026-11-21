package com.tunedplayer.app.ui.browse

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunedplayer.app.R
import com.tunedplayer.app.TunedApp
import com.tunedplayer.app.container
import com.tunedplayer.app.core.Search
import com.tunedplayer.app.core.TitleSort
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.StartTab
import com.tunedplayer.app.data.db.ItemEntity
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Choosing a sort on a large Movies library reorders it without freezing the screen (Melvin, 2026-11-20: "sorting on
 * the movies page or the tv page doesn't do anything", with the screen stopped for five seconds).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // text measured as on a device, as the screenshots are, so the grid lays out
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class MovieSortSpeedTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        runBlocking(Dispatchers.IO) {
            val db = app.container.db
            SampleData.seed(db)
            // No Continue watching row, so the grid's first item is a film, as for anyone who hasn't started one.
            db.library().clearHistory()
            // The app's database outlives a test here, so another test's films (the 50,000 of the speed test) go first.
            db.openHelper.writableDatabase.execSQL("DELETE FROM items WHERE id > $FIRST_ID")
            // Fifty thousand films ahead of the sample's, as a big provider has, in the playlist's order Film 50000 to Film 00001.
            val films = (1..FILMS).map { film(FIRST_ID + it, "Film %05d".format(FILMS + 1 - it), it - FILMS - 1) }
            db.items().insertAll(films)
            // As the screenshots do: Home reads some of its settings from the store as well as its parameter.
            app.container.settings.update { AppSettings(startTab = StartTab.MOVIES) }
        }
    }

    @Test
    fun choosingASortOnALargeLibraryDoesNotFreezeTheScreen() = chooseAToZ()

    private fun chooseAToZ() {
        val settings = AppSettings(startTab = StartTab.MOVIES)
        compose.setContent {
            HomeScreen(
                settings = settings,
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
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Film 50000").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(app.getString(R.string.sort_chip))).performClick()
        val started = System.nanoTime()
        compose.onNode(hasText(app.getString(R.string.sort_title))).performClick()
        // The list starts again at the top in the new order. Before, the grid kept the film that was first in view
        // (Compose keeps the first visible item by its key) and scrolled down to wherever it moved, so the screen
        // looked the same and the sort seemed to do nothing.
        compose.waitUntil(30_000) { shown("Film 00001") }
        compose.waitForIdle()
        val took = (System.nanoTime() - started) / 1_000_000
        println("MovieSortSpeedTest: sorted 50,000 films in $took ms")
        assertTrue(took < 3_000, "sorting took $took ms")
        assertTrue(shown("Film 00001") && shown("Film 00002"), "the new order's first films are on screen")
        assertFalse(shown("Film 50000"), "the old first film is no longer kept at the top")
    }

    private fun shown(name: String) = compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()

    private fun film(
        id: Long,
        name: String,
        order: Int,
    ) = ItemEntity(
        id = id,
        kind = 1,
        name = name,
        searchName = Search.normalize(name),
        groupName = "Drama",
        region = "EN",
        adult = false,
        logo = null,
        url = "http://example.invalid/movie/u/p/$id.mp4",
        tvgId = null,
        showKey = null,
        showName = null,
        season = null,
        episode = null,
        sortOrder = order,
        added = 0,
        year = null,
        rating = null,
        sortTitle = TitleSort.title(name),
    )

    private companion object {
        const val FILMS = 50_000
        const val FIRST_ID = 50_000L
    }
}
