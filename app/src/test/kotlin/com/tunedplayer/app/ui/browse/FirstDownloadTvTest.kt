package com.tunedplayer.app.ui.browse

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.ui.SyncText
import com.tunedplayer.app.ui.common.TvSafeArea
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On Melvin's TV (about 853 by 480 dp) the first download's seven numbered steps pushed the line under them, "Big
 * playlists can take a few minutes…", off the bottom of the screen (Melvin, 2026-11-21: "there's like descriptive text
 * that is slightly cut off at the very bottom"). Nothing on that screen takes focus, so a remote can't scroll to it:
 * every step and the line must show whole, inside the overscan margin, at once. It is drawn inside Home's rail and search
 * row, as on the TV, because those take the height the steps were short of.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w853dp-h480dp-land-television-xhdpi")
class FirstDownloadTvTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The longest plan there is: an Xtream login with a guide, seven steps. */
    private val steps = SyncProgress.plan(fromFile = false, xtream = true, portal = false, guide = true)

    @Test
    fun everyStepAndTheLineUnderThemFitOnATv() {
        val progress = mutableStateOf<SyncProgress?>(null)
        show(progress)
        // Whichever step is running, it is the one row with a bar and a line of detail, so each is tried in turn.
        steps.forEach { now ->
            progress.value = SyncProgress(now, 21_600_000, 48_000_000, rows = 12_345, steps = steps)
            compose.waitForIdle()
            steps.forEach { assertWhollyOnScreen(context.getString(SyncText.name(it))) }
            assertWhollyOnScreen(context.getString(R.string.first_run_keeps_going))
            assertWhollyOnScreen(context.getString(R.string.first_run_loading))
        }
    }

    @Test
    fun beforeTheFirstReportTheLineFitsToo() {
        show(mutableStateOf(null))
        assertWhollyOnScreen(context.getString(R.string.first_run_loading))
        assertWhollyOnScreen(context.getString(R.string.login_downloading))
    }

    private fun show(progress: State<SyncProgress?>) {
        compose.setContent {
            MaterialTheme {
                TvSafeArea {
                    Surface {
                        // Home's own chrome on a TV, which the first run sits inside: the rail and the search row above it.
                        Row(Modifier.fillMaxSize()) {
                            HomeRail(selected = 0, onSelect = {}, onOpenDownloads = {}, onOpenSettings = {})
                            Column(Modifier.weight(1f)) {
                                SearchBarRow(
                                    text = "",
                                    hint = "Search",
                                    onChange = {},
                                    grid = false,
                                    onToggleGrid = {},
                                    onOpenSearch = null,
                                    profile = null,
                                    onOpenProfiles = {},
                                )
                                FirstRun(refreshing = true, progress = progress.value, error = "", onRetry = {}, onChangeLogin = {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** The node's full bounds (unclipped by the scrolling column) sit inside the TV's overscan margin. */
    private fun assertWhollyOnScreen(text: String) {
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val margin =
            with(compose.density) {
                com.tunedplayer.app.ui.common.TV_VERTICAL_MARGIN
                    .toPx()
            }
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        node.assertIsDisplayed()
        val semantics = node.fetchSemanticsNode()
        val top = semantics.positionInRoot.y
        val bottom = top + semantics.size.height
        assertTrue("$text starts at $top, above the safe area's ${screen.top + margin}", top >= screen.top + margin - 1f)
        assertTrue("$text ends at $bottom, below the safe area's ${screen.bottom - margin}", bottom <= screen.bottom - margin + 1f)
    }
}
