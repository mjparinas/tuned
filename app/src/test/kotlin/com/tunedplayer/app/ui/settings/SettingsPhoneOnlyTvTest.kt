package com.tunedplayer.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunedplayer.app.R
import com.tunedplayer.app.TunedApp
import com.tunedplayer.app.data.AppSettings
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phone-only playback and guide settings stay off Android TV (landscape, PiP, mini player, background play, gestures,
 * pull to refresh), so the remote never lands on switches that do nothing there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SettingsPhoneOnlyTvTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test
    fun phoneOnlyPlaybackAndGuideRowsAreAbsent() {
        val vm = SettingsViewModel(app)
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                Column {
                    PlaybackPage(AppSettings(), vm)
                    GuidePage(AppSettings(), vm)
                }
            }
        }
        compose.waitForIdle()
        listOf(
            R.string.settings_landscape,
            R.string.settings_pip,
            R.string.settings_mini_player,
            R.string.settings_background_play,
            R.string.settings_gestures,
            R.string.settings_pull_to_refresh,
        ).forEach { id ->
            compose.onNodeWithText(app.getString(id)).assertDoesNotExist()
        }
        compose.onNodeWithText(app.getString(R.string.settings_media_buttons)).assertExists()
        compose.onNodeWithText(app.getString(R.string.settings_refresh_now)).assertExists()
    }

    @Test
    fun settingsSearchBoxIsAbsentOnTv() {
        val vm = SettingsViewModel(app)
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                SettingsScreen(AppSettings(), onBack = {}, onOpen = {}, onSignedOut = {}, vm = vm)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.settings_search)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.settings_page_playlists)).assertExists()
    }
}
