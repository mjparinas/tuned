package com.tunedplayer.app.ui.browse

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunedplayer.app.core.ContentKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Live TV's chips read the pinned groups while the ViewModel is built. Declared below the channel list, the pins were
 * still null then, and opening Live TV crashed with a NullPointerException (found by the screenshots,
 * docs/decisions/screenshots.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowseViewModelTest {
    @Test
    fun liveTvChipsLoadWithoutCrashing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val vm = BrowseViewModel(app)
        var chips: List<String>? = null
        val watching =
            CoroutineScope(Dispatchers.Main).launch {
                vm.groups(ContentKind.LIVE).collect { list -> chips = list.map { it.groupName } }
            }
        // Runs the chips' collection on the main thread, where the null pins threw.
        shadowOf(Looper.getMainLooper()).idle()
        watching.cancel()
        assertTrue(chips != null)
    }
}
