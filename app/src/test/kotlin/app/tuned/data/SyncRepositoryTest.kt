package app.tuned.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.tuned.data.db.TunedDatabase
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals

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
}
