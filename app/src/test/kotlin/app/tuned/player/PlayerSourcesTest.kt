package app.tuned.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals

/**
 * A finished download plays from the device (Melvin, 2026-11-17): tapping a saved episode while the rest of the
 * season downloaded failed, because the player's only data source was OkHttp, which can't open a `file://` URI.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerSourcesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `a saved file is read from the device`() {
        val bytes = ByteArray(4_096) { it.toByte() }
        val file = folder.newFile("42.mkv").apply { writeBytes(bytes) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = PlayerSources.factory(context, OkHttpClient(), "Tuned/test").createDataSource()
        source.open(DataSpec(Uri.fromFile(file)))
        val read = ByteArray(bytes.size)
        var at = 0
        while (at < read.size) {
            val n = source.read(read, at, read.size - at)
            if (n < 0) break
            at += n
        }
        source.close()
        assertContentEquals(bytes, read)
    }
}
