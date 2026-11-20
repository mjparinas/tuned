package com.tunedplayer.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.core.TitleSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Melvin, 2026-11-20: "sorting on the movies page or the tv page doesn't do anything". The backup runs as the app opens;
 * it joins the backup with this device's copy as it was when the run began, then puts the joined settings back. A sort
 * picked in those seconds went back to the playlist's order, so it looked as if picking it did nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsRepository(context)

    @Test
    fun aSortPickedWhileTheBackupJoinsIsKept() = runBlocking {
        settings.update { it.copy(movieSort = TitleSort.Order.PROVIDER, guideDays = 3) }
        // The backup run takes this device's copy, then waits on the server.
        val taken = settings.snapshot()
        // Meanwhile Movies is sorted A to Z.
        settings.update { it.copy(movieSort = TitleSort.Order.TITLE) }
        // The joined copy: this device's as it was taken, with a change another device made.
        val joined = taken + ("guide_days" to 7)
        settings.restore(joined, since = taken)
        val now = settings.settings.first()
        assertEquals(TitleSort.Order.TITLE, now.movieSort, "the sort picked during the run stays")
        assertEquals(7, now.guideDays, "the other device's change still comes in")
    }

    @Test
    fun withoutAnEarlierCopyEverySettingIsTaken() = runBlocking {
        settings.update { it.copy(movieSort = TitleSort.Order.TITLE) }
        settings.restore(mapOf("movie_sort" to TitleSort.Order.RATING.name))
        assertEquals(TitleSort.Order.RATING, settings.settings.first().movieSort)
    }
}
