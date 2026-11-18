package app.tuned.player

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The player's controls hid after three seconds even with TalkBack on, before a screen reader could reach the buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibleControlsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val a11y = shadowOf(context.getSystemService(AccessibilityManager::class.java))

    @Test
    fun withoutAScreenReaderTheChosenTimeStands() {
        assertEquals(3_000, AccessibleControls.timeoutMs(context, 3_000))
    }

    @Test
    fun withAScreenReaderTheControlsStayUntilTapped() {
        a11y.setEnabled(true)
        a11y.setTouchExplorationEnabled(true)
        assertEquals(AccessibleControls.STAY, AccessibleControls.timeoutMs(context, 3_000))
    }
}
