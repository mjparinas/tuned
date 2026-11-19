package app.tuned.data

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.tuned.TunedApp
import app.tuned.container
import app.tuned.data.db.DownloadEntity
import app.tuned.data.db.FavouriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Signing out puts the app back as it was installed; the downloads go or stay as the person chose. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppResetTest {
    private val app: TunedApp = ApplicationProvider.getApplicationContext()
    private val c get() = app.container
    private val downloads get() = app.container.db.downloads()
    private val library get() = app.container.db.library()
    private val offline get() = File(requireNotNull(app.getExternalFilesDir("offline")), "1.mp4")
    private val partial get() = File(requireNotNull(app.getExternalFilesDir("offline")), "2.mp4.part")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        runBlocking(Dispatchers.IO) {
            c.settings.update { it.copy(theme = ThemeMode.LIGHT, accent = Accent.entries.last()) }
            c.settings.setAdultPin("1234")
            library.addFavourite(FavouriteEntity(1, addedAt = 1))
            offline.writeText("film")
            partial.writeText("half")
            downloads.upsert(DownloadEntity(1, "Heat", "http://h/u/p/1.mp4", offline.path, state = DownloadEntity.DONE, addedAt = 1))
            downloads.upsert(DownloadEntity(2, "Ronin", "http://h/u/p/2.mp4", partial.path.removeSuffix(".part"), addedAt = 2))
        }
    }

    @Test
    fun deleteResetsEverythingAndRemovesTheDownloads() = runBlocking(Dispatchers.IO) {
        assertEquals(2, c.reset.saved().count)

        c.reset.signOut(keepDownloads = false)

        val s = c.settings.settings.first()
        assertEquals(AppSettings().theme, s.theme)
        assertEquals(AppSettings().accent, s.accent)
        assertFalse(c.settings.hasAdultPin.first())
        assertEquals(emptyList(), library.favouriteIds().first())
        assertEquals(0, downloads.count())
        assertFalse(offline.exists())
        assertFalse(partial.exists())
        assertFalse(c.reset.pending)
    }

    @Test
    fun keepLeavesFinishedDownloadsOnly() = runBlocking(Dispatchers.IO) {
        c.reset.signOut(keepDownloads = true)

        val kept = downloads.all().first()
        assertEquals(listOf(1L), kept.map { it.itemId })
        assertEquals("", kept.single().url) // the stream address can hold the login
        assertTrue(offline.exists())
        assertFalse(partial.exists())
        assertFalse(c.settings.hasAdultPin.first())
    }

    @Test
    fun aResetCutShortFinishesAtTheNextStart() = runBlocking(Dispatchers.IO) {
        // As if the app was closed straight after the sign-out began.
        val prefs = app.getSharedPreferences("tuned.reset", 0)
        prefs.edit(commit = true) { putBoolean("pending_keep_downloads", false) }
        assertTrue(c.reset.pending)

        c.reset.resumeIfPending()
        repeat(WAIT_STEPS) { if (c.reset.pending) Thread.sleep(STEP_MS) }

        assertFalse(c.reset.pending)
        assertFalse(c.settings.hasAdultPin.first())
        assertEquals(0, downloads.count())
    }

    private companion object {
        const val WAIT_STEPS = 100
        const val STEP_MS = 50L
    }
}
