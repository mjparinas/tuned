package app.tuned.premium

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `github` build (the one CI tests) has everything unlocked and sells nothing, so the copies people already
 * installed from GitHub lose no feature when Premium arrives (Melvin, 2026-11-17: GitHub releases stay free with
 * everything unlocked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PremiumTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun githubBuildIsAlwaysUnlocked() = runBlocking {
        val premium = Premium(context, this)
        assertTrue(Premium.ALWAYS)
        assertTrue(premium.unlocked.value)
        assertEquals(true, premium.restore())
        assertTrue(premium.store.offers().isEmpty())
    }

    @Test
    fun allowNeverOpensThePremiumScreenWhenUnlocked() = runBlocking {
        val premium = Premium(context, this)
        assertTrue(premium.allow(context))
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }
}
