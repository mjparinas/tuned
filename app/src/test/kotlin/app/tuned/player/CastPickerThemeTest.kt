package app.tuned.player

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import app.tuned.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Tapping the cast button crashed the app (Melvin, 2026-11-17): the device picker is a media router dialog built
 * from the player's theme, and it throws unless that theme carries AppCompat's opaque `colorPrimary`. The platform
 * theme the player used had none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastPickerThemeTest {
    @Test
    fun thePlayersThemeHasWhatTheDevicePickerNeeds() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Tuned_Player)

        // AppCompat's attribute, looked up by name: the app compiles against Media3 and the media router, not AppCompat.
        @Suppress("DiscouragedApi")
        val colorPrimary = context.resources.getIdentifier("colorPrimary", "attr", context.packageName)
        assertTrue(colorPrimary != 0)
        val value = TypedValue()
        assertTrue(context.theme.resolveAttribute(colorPrimary, value, true), "the player's theme has no colorPrimary")
        assertTrue(value.data ushr 24 == 0xFF, "colorPrimary must be opaque for the picker")
    }
}
