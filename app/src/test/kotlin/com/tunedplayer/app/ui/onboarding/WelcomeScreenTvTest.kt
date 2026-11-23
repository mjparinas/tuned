package com.tunedplayer.app.ui.onboarding

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
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
 * every kind (the phone card first) and the privacy line, with nothing scrolled away. A card is measured whole, not
 * where its column clips it: the MAC portal card once ran off the bottom of the column on Melvin's TV and still passed,
 * because the part that showed was on screen (Melvin, 2026-11-20: "Right side at the bottom is cut off").
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
            MaterialTheme { TvSafeArea { WelcomeScreen(firstRun = true, onPick = {}, onBack = {}, onPhone = {}) } }
        }
        listOf(
            R.string.welcome_title,
            R.string.tv_signin_card,
            R.string.kind_xtream,
            R.string.kind_link,
            R.string.kind_file,
            R.string.kind_stalker,
            R.string.restore_account,
            R.string.login_privacy,
        ).forEach(::assertWhollyOnScreen)
    }

    /** Text larger than the layout planned for (a TV's font size setting) scrolls, and the last card can still be reached whole. */
    @Test
    @Config(fontScale = 1.3f)
    fun withLargerTextTheLastCardScrollsWhollyIntoView() {
        compose.setContent {
            MaterialTheme { TvSafeArea { WelcomeScreen(firstRun = true, onPick = {}, onBack = {}, onPhone = {}) } }
        }
        compose.onNodeWithText(text(R.string.kind_stalker)).performScrollTo()
        assertWhollyOnScreen(R.string.kind_stalker)
    }

    /** The node's full bounds (unclipped by a scrolling column) sit inside the TV's overscan margin. */
    private fun assertWhollyOnScreen(id: Int) {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val margin =
            with(compose.density) {
                com.tunedplayer.app.ui.common.TV_VERTICAL_MARGIN
                    .toPx()
            }
        val node = compose.onNodeWithText(text(id))
        node.assertIsDisplayed()
        val semantics = node.fetchSemanticsNode()
        val top = semantics.positionInRoot.y
        val bottom = top + semantics.size.height
        assertTrue("${text(id)} starts at $top, above the safe area's ${screen.top + margin}", top >= screen.top + margin - 1f)
        assertTrue("${text(id)} ends at $bottom, below the safe area's ${screen.bottom - margin}", bottom <= screen.bottom - margin + 1f)
    }
}
