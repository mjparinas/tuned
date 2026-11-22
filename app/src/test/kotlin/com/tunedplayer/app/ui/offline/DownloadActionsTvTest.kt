package com.tunedplayer.app.ui.offline

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import com.tunedplayer.app.data.db.ItemEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android TV leaves every download action out of film, episode and season pages (Melvin, 2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class DownloadActionsTvTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun itemAndSeasonDownloadActionsAreAbsent() {
        val episode =
            ItemEntity(
                id = 9_001,
                kind = 2,
                name = "Episode 1",
                searchName = "episode 1",
                groupName = "Shows",
                region = null,
                adult = false,
                logo = null,
                url = "https://example.invalid/episode.mp4",
                tvgId = null,
                showKey = "show",
                showName = "Show",
                season = 1,
                episode = 1,
                sortOrder = 0,
            )

        compose.setContent {
            MaterialTheme {
                Column {
                    DownloadButton(episode)
                    SeasonDownloadButton(listOf(episode))
                }
            }
        }

        compose.onNodeWithContentDescription(context.getString(R.string.download)).assertDoesNotExist()
        compose
            .onNodeWithText(context.resources.getQuantityString(R.plurals.download_season, 1, 1))
            .assertDoesNotExist()
    }
}
