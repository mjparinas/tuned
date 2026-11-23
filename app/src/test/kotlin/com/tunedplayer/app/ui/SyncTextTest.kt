package com.tunedplayer.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.data.SyncProgress
import com.tunedplayer.app.data.SyncProgress.Step
import com.tunedplayer.app.work.SyncWorker
import com.tunedplayer.app.work.SyncWorker.Companion.toData
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The words for a running refresh: its step's number and name, and what it has got through. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncTextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val steps = SyncProgress.plan(fromFile = false, xtream = true, portal = false, guide = true)

    @Test
    fun aKnownSizeShowsHowMuchOfIt() {
        val line = SyncText.describe(context, SyncProgress(Step.DownloadPlaylist, 12_400_000, 48_000_000, steps = steps))
        assertEquals("Step 1 of 7 · Downloading channels · 12 MB of 48 MB", line)
    }

    @Test
    fun aKnownSizeAddsHowMuchLongerWhenItHasBeenMoving() {
        // 12.4 of 48 MB in 10s → about 29s left at that rate → "about 30 sec left".
        val line =
            SyncText.describe(
                context,
                SyncProgress(Step.DownloadPlaylist, 12_400_000, 48_000_000, steps = steps, startedAt = 1_000),
                now = 11_000,
            )
        assertEquals("Step 1 of 7 · Downloading channels · 12 MB of 48 MB · about 30 sec left", line)
    }

    @Test
    fun anUnknownSizeShowsWhatHasArrived() {
        val line = SyncText.describe(context, SyncProgress(Step.DownloadGuide, 3_100_000, steps = steps))
        assertEquals("Step 5 of 7 · Downloading the guide · 3.1 MB so far", line)
    }

    @Test
    fun beforeTheServerAnswersItSaysSo() {
        assertEquals("Waiting for the server…", SyncText.detail(context, SyncProgress(Step.DownloadPlaylist, steps = steps)))
    }

    @Test
    fun readingShowsHowFarThroughTheFileAndHowManyFound() {
        val line = SyncText.describe(context, SyncProgress(Step.ReadPlaylist, 45, 100, rows = 12_345, steps = steps))
        assertEquals("Step 2 of 7 · Processing channels · 45% · 12,345 items", line)
    }

    @Test
    fun savingWithoutACountIsJustItsName() {
        assertEquals("Step 7 of 7 · Saving the guide", SyncText.describe(context, SyncProgress(Step.SaveGuide, steps = steps)))
    }

    @Test
    fun aStepTheStepsLeftOutIsCountedOnTheEnd() {
        val progress = SyncProgress(Step.Library, steps = listOf(Step.ReadPlaylist, Step.SavePlaylist))
        assertEquals(3, progress.number)
        assertEquals(3, progress.count)
    }

    /** What the worker hands the screens comes back whole, steps and all. */
    @Test
    fun progressSurvivesTheWorker() {
        val progress = SyncProgress(Step.ReadGuide, 10, 20, rows = 5, steps = steps)
        assertEquals(progress, SyncWorker.progressOf(progress.toData()))
    }
}
