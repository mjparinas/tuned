package com.tunedplayer.app.player

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Faster channel zapping (Melvin, 2026-11-28): the last channel's kept pieces leave the folder at once on a channel
 * change, rather than being deleted one by one before the next stream may open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveBufferResetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val parent = File(context.cacheDir, "zap-test").apply { mkdirs() }
    private val dir = File(parent, "live")
    private val buffer = LiveBuffer(dir, keepMs = 60_000, maxBytes = 100_000_000)

    @After fun clean() {
        buffer.release()
        parent.deleteRecursively()
    }

    @Test
    fun aNewChannelStartsWithAnEmptyFolderAtOnce() {
        dir.mkdirs()
        repeat(200) { File(dir, "piece-$it.ts").writeBytes(ByteArray(1_024)) }
        buffer.reset(Uri.parse("http://example.test/live/u/p/2.ts"))
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun closingThePlayerLeavesNoOldPiecesBehind() {
        dir.mkdirs()
        File(dir, "piece-1.ts").writeBytes(ByteArray(10))
        buffer.reset(Uri.parse("http://example.test/live/u/p/2.ts"))
        buffer.release()
        assertEquals(emptyList(), parent.listFiles().orEmpty().map { it.name })
    }
}
