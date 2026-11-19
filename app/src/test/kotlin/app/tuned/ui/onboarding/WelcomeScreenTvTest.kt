package app.tuned.ui.onboarding

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.tuned.R
import app.tuned.ui.common.TvSafeArea
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a real TV the welcome screen's single column was taller than the screen, so the logo sat cut off at the top edge
 * (Melvin, 2026-11-19). On a TV-sized screen, inside the TV's overscan margin, everything shows at once: the welcome,
 * all three kinds and the privacy line, with nothing scrolled away.
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
        compose.onNodeWithText(text(R.string.welcome_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.kind_xtream)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.kind_file)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.login_privacy)).assertIsDisplayed()
    }
}
