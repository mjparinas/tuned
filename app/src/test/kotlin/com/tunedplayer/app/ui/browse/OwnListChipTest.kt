package com.tunedplayer.app.ui.browse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import com.tunedplayer.app.data.db.ListEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OwnListChipTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val list = ListEntity(id = 1, name = "Weekend", position = 0, createdAt = 1, profileId = 1)

    @Test
    fun selectedListChipIsOnlyAFilter() {
        compose.setContent {
            MaterialTheme {
                OwnListChip(list = list, selected = true, onSelect = {})
            }
        }

        compose.onNodeWithText("Weekend").assertExists()
        compose.onNodeWithText(context.getString(R.string.list_rename)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.list_delete)).assertDoesNotExist()
    }
}
