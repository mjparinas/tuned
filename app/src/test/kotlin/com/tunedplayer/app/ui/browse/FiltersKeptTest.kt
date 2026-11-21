package com.tunedplayer.app.ui.browse

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.tunedplayer.app.container
import com.tunedplayer.app.core.ChannelQuality
import com.tunedplayer.app.core.ContentKind
import com.tunedplayer.app.core.LiveTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Filters chosen on the browse tabs stay chosen when the app is closed and opened again (a new ViewModel), and belong
 * to the profile that chose them (Melvin, 2026-11-21: "Filters should persist until changed, even when the app is closed").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FiltersKeptTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = app.container.browseFilters

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test
    fun filtersComeBackAfterTheAppIsClosed() {
        val first = BrowseViewModel(app)
        waitFor { first.filtersRestored.value }
        first.filter(ContentKind.LIVE).value = Filter.Favourites
        first.liveQuick.value = LiveQuick(topic = LiveTopic.SPORTS, quality = ChannelQuality.HD)
        waitFor { SavedFilters.decode(runBlocking(Dispatchers.IO) { store.get(1) }).live.topic == LiveTopic.SPORTS }

        val reopened = BrowseViewModel(app)
        waitFor { reopened.liveQuick.value.topic == LiveTopic.SPORTS }
        assertEquals(Filter.Favourites, reopened.filter(ContentKind.LIVE).value)
        assertEquals(ChannelQuality.HD, reopened.liveQuick.value.quality)
    }

    @Test
    fun anotherProfileStartsWithNothingChosen() = runBlocking(Dispatchers.IO) {
        store.set(1, SavedFilters.encode(SavedFilters(chips = mapOf(ContentKind.LIVE to Filter.Favourites))))
        assertNull(store.get(2))
        store.forget(1)
        assertNull(store.get(1))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(done: () -> Boolean) {
        repeat(200) {
            idle()
            if (done()) return
            Thread.sleep(20)
        }
        fail("Timed out")
    }
}
