package com.tunedplayer.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * A TV remote's focus in a long list (Melvin, 2026-11-19: "Optimize TV for remotes"): it starts on the first item, and
 * the page and channel keys jump a screenful instead of one row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
@OptIn(ExperimentalTestApi::class)
class TvFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun list() {
        compose.setContent {
            val state = rememberLazyListState()
            val focus = rememberTvFocus()
            LazyColumn(Modifier.fillMaxSize().tvPagedList(focus, state), state = state) {
                items(count = 500, key = { it.toLong() }) { index ->
                    Text(
                        "Channel $index",
                        Modifier
                            .tvFocusItem(focus, index.toLong(), first = index == 0)
                            .fillMaxWidth()
                            .height(60.dp)
                            .clickable {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun focusStartsOnTheFirstItem() {
        list()
        compose.onNodeWithText("Channel 0").assertIsFocused()
    }

    @Test
    fun pageDownJumpsAScreenful() {
        list()
        compose.onNodeWithText("Channel 0").performKeyInput { pressKey(Key.PageDown) }
        compose.waitForIdle()
        // Channel 0 has scrolled off, so it is gone or at least no longer focused.
        compose.onAllNodes(hasText("Channel 0") and isFocused()).assertCountEquals(0)
        // About nine 60 dp rows fit the 540 dp screen, so one press goes well past the next row.
        val landed =
            compose
                .onNode(isFocused())
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .first()
                .text
                .removePrefix("Channel ")
                .toInt()
        assertTrue(landed >= 7, "landed on $landed")
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.ChannelUp) }
        compose.waitForIdle()
        compose.onNodeWithText("Channel 0").assertIsFocused()
    }
}
