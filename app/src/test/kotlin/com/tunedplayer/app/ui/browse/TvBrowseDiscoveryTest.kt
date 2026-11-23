package com.tunedplayer.app.ui.browse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * On Android TV, posters never expose a long-press menu (OK opens the page, where every action already sits). Search
 * stays on the rail rather than duplicated above each browse tab.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class TvBrowseDiscoveryTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun posterHasNoLongPressMenuOnTv() {
        compose.setContent {
            MaterialTheme {
                Poster(
                    title = "Film",
                    image = null,
                    onClick = {},
                    onToggleFavourite = {},
                    onPlayNow = {},
                    extraMenu = listOf(context.getString(R.string.list_add) to {}),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Film").assertExists()
        val withLongClick =
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertTrue(withLongClick.isEmpty(), "TV posters must not advertise a long-press menu")
    }
}
