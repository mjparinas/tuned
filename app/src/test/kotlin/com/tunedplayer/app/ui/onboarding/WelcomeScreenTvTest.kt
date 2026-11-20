package com.tunedplayer.app.ui.onboarding

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import com.tunedplayer.app.ui.common.TvSafeArea
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a real TV the welcome screen's single column was taller than the screen, so the logo sat cut off at the top edge
 * (Melvin, 2026-11-19). On a TV-sized screen, inside the TV's overscan margin, everything shows at once: the welcome,
 * all four kinds and the privacy line, with nothing scrolled away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w853dp-h480dp-land-television-xhdpi")
class WelcomeScreenTvTest {
    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun theWholeWelcomeFitsOnATv() {
        compose.setContent {
            MaterialTheme { TvSafeArea { WelcomeScreen(firstRun = true, onPick = {}, onBack = {}) } }
        }
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        listOf(
            R.string.welcome_title,
            R.string.kind_xtream,
            R.string.kind_link,
            R.string.kind_file,
            R.string.kind_stalker_summary,
            R.string.restore_account,
            R.string.login_privacy,
        ).forEach { id ->
            val node = compose.onNodeWithText(text(id))
            node.assertIsDisplayed()
            // Whole, not just partly on screen: a card cut off at the bottom edge used to pass.
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("${text(id)} ends at ${bounds.bottom}, below the screen's ${screen.bottom}", bounds.bottom <= screen.bottom)
        }
    }
}
