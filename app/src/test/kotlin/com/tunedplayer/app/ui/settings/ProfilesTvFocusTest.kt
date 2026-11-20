package com.tunedplayer.app.ui.settings

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunedplayer.app.TunedApp
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.data.db.CurrentProfileEntity
import com.tunedplayer.app.data.db.ProfileEntity
import com.tunedplayer.app.data.db.Profiles
import com.tunedplayer.app.ui.theme.TunedTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a TV, "Who's watching?" opens with the remote on the first profile, so OK alone picks it (Melvin, 2026-11-20:
 * "it should auto highlight the first entry so we only need to click ok on the remote"). The profile in use is the
 * second here, so the test shows it is the first entry, not the current one, that gets focus.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ProfilesTvFocusTest {
    @get:Rule val compose = createComposeRule()

    private val app: TunedApp = ApplicationProvider.getApplicationContext()

    @Before
    fun seed() {
        // As for the screenshots: the app turns WorkManager's start-up initializer off, and its container asks for it.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        runBlocking(Dispatchers.IO) {
            val db = app.container.db
            db.clearAllTables()
            db.profiles().insert(ProfileEntity(Profiles.FIRST, "Alex", 0, kids = false, pinHash = null, createdAt = 0))
            val second = db.profiles().insert(ProfileEntity(name = "Sam", colour = 1, kids = false, pinHash = null, createdAt = 0))
            db.profiles().setCurrent(CurrentProfileEntity(profileId = second))
        }
    }

    private fun picker() {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                ProfilesScreen(atStart = true, onPicked = {}, onBack = {})
            }
        }
        val shown = runCatching { compose.waitUntil(15_000) { compose.onAllNodes(hasText("Sam")).fetchSemanticsNodes().isNotEmpty() } }
        // On a timeout, the failure says what was on screen instead.
        if (shown.isFailure) {
            throw AssertionError("The profiles never showed:\n" + compose.onRoot().printToString(), shown.exceptionOrNull())
        }
        compose.waitForIdle()
    }

    @Test
    fun firstProfileHasFocusOnATv() {
        picker()
        compose.onNodeWithText("Alex").assertIsFocused()
        compose.onNodeWithText("Sam").assertIsNotFocused()
    }

    /** A phone is touched, not steered, so nothing is focused there. */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun nothingIsFocusedOnAPhone() {
        picker()
        compose.onNodeWithText("Alex").assertIsNotFocused()
    }
}
