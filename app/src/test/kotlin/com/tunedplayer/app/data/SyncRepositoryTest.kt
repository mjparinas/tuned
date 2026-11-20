package com.tunedplayer.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tunedplayer.app.data.db.TunedDatabase
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A plain playlist's catch-up comes from its own attributes, read as the file is saved. A refresh that finds the same
 * file skips the saving, and used to wipe the catch-up with it, so Watch from start vanished on the second refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TunedDatabase::class.java).allowMainThreadQueries().build()
    private val dir = File(context.cacheDir, "sync-test").apply { deleteRecursively() }

    @After
    fun close() = db.close()

    @Test
    fun anUnchangedPlaylistKeepsItsCatchUp() = runTest {
        val playlist =
            File(context.filesDir, "playlists/catchup.m3u").apply {
                parentFile?.mkdirs()
                writeText(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="news.ca" group-title="News" catchup="default" catchup-days="3" catchup-source="http://h.example/arch/news?utc={utc}",News
                    http://h.example/live/news.ts
                    """.trimIndent(),
                )
            }
        val login = Credentials("", "", "", playlist.toURI().toString())
        val sync = SyncRepository(db, OkHttpClient(), { login }, SettingsRepository(context), dir)

        sync.refreshAll()
        assertEquals(listOf(3), db.catchUp().all().map { it.days })

        sync.refreshAll()
        assertEquals(listOf(3), db.catchUp().all().map { it.days })
    }

    /**
     * Melvin, 2026-11-20: the first download showed only a moving bar. Each report now names its step among the
     * refresh's numbered steps, and a download the server gives a size for counts towards that size.
     */
    @Test
    fun aDownloadReportsItsSizeAndEachStepInOrder() = runTest {
        val body = "#EXTM3U\n" + (1..50).joinToString("") { "#EXTINF:-1 group-title=\"News\",News $it\nhttp://h.example/live/$it.ts\n" }
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body(body).build())
        server.start()
        try {
            val login = Credentials("", "", "", server.url("/list.m3u").toString())
            val reports = mutableListOf<SyncProgress>()
            SyncRepository(db, OkHttpClient(), { login }, SettingsRepository(context), dir).refreshAll { reports += it }

            val plan = listOf(SyncProgress.Step.DownloadPlaylist, SyncProgress.Step.ReadPlaylist, SyncProgress.Step.SavePlaylist)
            // A link with no guide in its header has no guide steps, and every report says so.
            assertTrue(reports.all { it.steps == plan }, reports.toString())
            // Waiting for the server first, then the bytes against the size it gave.
            assertTrue(reports.first().waiting)
            val downloaded = reports.last { it.step == SyncProgress.Step.DownloadPlaylist }
            assertEquals(body.length.toLong(), downloaded.total)
            assertEquals(body.length.toLong(), downloaded.done)
            // Reading counts through the saved file and the channels found; saving comes after.
            val read = reports.last { it.step == SyncProgress.Step.ReadPlaylist }
            assertEquals(50, read.rows)
            assertEquals(body.length.toLong(), read.total)
            assertEquals(plan, reports.map { it.step }.distinct())
            assertEquals(3, reports.last().number)
        } finally {
            server.close()
        }
    }

    /** A playlist file on the device isn't downloaded: its steps start at reading it. */
    @Test
    fun aFileStartsAtReading() = runTest {
        val playlist =
            File(context.filesDir, "playlists/plain.m3u").apply {
                parentFile?.mkdirs()
                writeText("#EXTM3U\n#EXTINF:-1,News\nhttp://h.example/live/news.ts\n")
            }
        val login = Credentials("", "", "", playlist.toURI().toString())
        val reports = mutableListOf<SyncProgress>()
        SyncRepository(db, OkHttpClient(), { login }, SettingsRepository(context), dir).refreshAll { reports += it }

        assertEquals(listOf(SyncProgress.Step.ReadPlaylist, SyncProgress.Step.SavePlaylist), reports.first().steps)
        assertEquals(SyncProgress.Step.ReadPlaylist, reports.first().step)
        assertEquals(1, reports.last { it.step == SyncProgress.Step.ReadPlaylist }.rows)
    }
}
