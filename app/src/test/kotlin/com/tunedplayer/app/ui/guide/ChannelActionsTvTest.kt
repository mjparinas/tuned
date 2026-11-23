package com.tunedplayer.app.ui.guide

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.R
import com.tunedplayer.app.ui.browse.ChannelEditPicker
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Android TV never offers or opens channel rename or logo change (Melvin, 2026-11-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ChannelActionsTvTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun channelPageLeavesOutRenameAndChangeLogoButKeepsAddToList() {
        compose.setContent { MaterialTheme { ChannelActions(channelId = 1) } }

        compose.onNodeWithText(context.getString(R.string.channel_rename)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.channel_change_logo)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.list_add)).assertIsDisplayed()
    }

    @Test
    fun renameAndLogoPickersRefuseTheActionDefensively() {
        var openedPremium = false
        val picker = ChannelEditPicker({ false }, { false }, { false }, { openedPremium = true })

        picker.rename(1)
        picker.changeLogo(1)

        assertNull(picker.renaming)
        assertNull(picker.changingLogo)
        assertFalse(openedPremium)
    }
}

/** Phones and tablets retain channel renaming and logo changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-notnight-xxhdpi")
class ChannelActionsPhoneTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun channelPageStillOffersRenameAndChangeLogo() {
        compose.setContent { MaterialTheme { ChannelActions(channelId = 1) } }

        compose.onNodeWithText(context.getString(R.string.channel_rename)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.channel_change_logo)).assertIsDisplayed()
    }
}
