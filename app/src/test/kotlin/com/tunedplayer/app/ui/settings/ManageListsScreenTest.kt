package com.tunedplayer.app.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import com.tunedplayer.app.data.ThemeMode
import com.tunedplayer.app.data.db.ListEntity
import com.tunedplayer.app.ui.common.rememberStartFocus
import com.tunedplayer.app.ui.theme.TunedTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ManageListsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val lists = listOf(list(1, "Weekend"), list(2, "Sport"))

    @Test
    fun theFirstListStartsFocusedAndOpensRename() {
        content()

        compose.onNodeWithText("Weekend").assertIsFocused().performClick()
        compose.onNodeWithText(context.getString(R.string.list_rename)).assertExists()
    }

    @Test
    fun deleteConfirmsBeforeRemovingOnlyThatList() {
        var deleted: Long? = null
        content(onDelete = { deleted = it })

        compose.onAllNodesWithContentDescription(context.getString(R.string.list_delete))[0].performClick()
        compose.onNodeWithText(context.getString(R.string.list_delete_title, "Weekend")).assertExists()
        compose.onNodeWithText(context.getString(R.string.list_delete)).performClick()
        assertEquals(1L, deleted)
    }

    private fun content(onDelete: (Long) -> Unit = {}) {
        compose.setContent {
            TunedTheme(mode = ThemeMode.DARK) {
                CompositionLocalProvider(LocalRowStartFocus provides rememberStartFocus()) {
                    ManageListsContent(lists, onRename = { _, _ -> }, onDelete = onDelete)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun list(
        id: Long,
        name: String,
    ) = ListEntity(id, name, position = id, createdAt = id)
}
