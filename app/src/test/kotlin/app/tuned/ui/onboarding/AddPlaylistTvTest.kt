package app.tuned.ui.onboarding

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import app.tuned.R
import app.tuned.data.Credentials
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a TV the Xtream password shows as typed, with a row the remote can reach to hide it (Melvin, 2026-11-19: "dont
 * hide the password… we dont know if we mistyped it").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class AddPlaylistTvTest {
    @get:Rule
    val compose = createComposeRule()

    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    @Test
    fun thePasswordShowsOnATv() {
        compose.setContent {
            MaterialTheme { AddPlaylistScreen(kind = Credentials.Kind.Xtream, editIndex = -1, onBack = {}, onDone = {}) }
        }
        compose.onNodeWithText(text(R.string.login_password)).performTextInput("hunter2")
        // The eye in the field names what it would do next: "Hide password" while the password shows.
        compose.onNodeWithContentDescription(text(R.string.login_hide_password)).assertExists()
        val row = compose.onNodeWithText(text(R.string.login_show_password))
        row.assertIsOn()
        row.performClick()
        row.assertIsOff()
        compose.onNodeWithContentDescription(text(R.string.login_show_password)).assertExists()
    }
}
