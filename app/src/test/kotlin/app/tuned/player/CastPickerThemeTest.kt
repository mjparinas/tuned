package app.tuned.player

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.test.core.app.ApplicationProvider
import app.tuned.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tapping the cast button crashed the app (Melvin, 2026-11-17): the device picker is a dialog built from the
 * player's theme, and it throws on a theme without AppCompat's colours. Building it from that theme must work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CastPickerThemeTest {
    @Test
    fun theDevicePickerOpensOnThePlayersTheme() {
        val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Tuned_Player)
        MediaRouteChooserDialog(context).create()
    }
}
