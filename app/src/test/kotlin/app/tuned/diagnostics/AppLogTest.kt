package app.tuned.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLogTest {
    @After
    fun reset() {
        AppLog.setSecrets()
        AppLog.clear()
    }

    @Test
    fun `never keeps the login in use`() {
        AppLog.setSecrets("s3cretpw", "alice")
        AppLog.e("Sync", "Failed for alice", IllegalStateException("bad password s3cretpw"))
        val text = AppLog.snapshot().joinToString { it.message }
        assertFalse("alice" in text || "s3cretpw" in text, text)
        assertTrue("•••" in text)
    }

    @Test
    fun `masks login-shaped addresses without knowing the login`() {
        AppLog.w("Player", "Source error http://tv.example/live/bob/pw123/1.ts")
        assertEquals("Source error http://tv.example/live/•••/•••/1.ts", AppLog.snapshot().single().message)
    }

    @Test
    fun `keeps only the newest lines`() {
        repeat(1_200) { AppLog.w("T", "line $it") }
        val lines = AppLog.snapshot()
        assertEquals(1_000, lines.size)
        assertEquals("line 1199", lines.last().message)
    }

    @Test
    fun `attempt logs a failure and returns it`() {
        val result = attempt<Int>("T", "Reading failed") { error("disk") }
        assertTrue(result.isFailure)
        assertEquals("Reading failed", AppLog.snapshot().single().message.lineSequence().first())
    }

    @Test
    fun `attempt never swallows cancellation`() {
        assertFailsWith<CancellationException> { attempt("T", "x") { throw CancellationException("stop") } }
        assertTrue(AppLog.snapshot().isEmpty())
    }

    @Test
    fun `launchSafely logs a failure instead of crashing the scope`() = runTest {
        val parent = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        parent.launchSafely("VM") { error("boom") }
        var after = false
        parent.launchSafely("VM") { after = true }
        testScheduler.advanceUntilIdle()
        assertTrue(after, "the scope keeps working after a failure")
        assertTrue(AppLog.snapshot().any { it.tag == "VM" && "boom" in it.message })
    }
}
