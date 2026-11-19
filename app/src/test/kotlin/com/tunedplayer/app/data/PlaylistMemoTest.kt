package com.tunedplayer.app.data

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Every refresh used to save the whole playlist again ("Saving channels" for minutes) even when the server sent the
 * very same file. These check that an unchanged file is recognised, and that anything else is still saved.
 */
class PlaylistMemoTest {
    @get:Rule val folder = TemporaryFolder()

    private fun memo(salt: String = "1004000") = PlaylistMemo(folder.root.resolve("playlist.fingerprint"), salt)

    private fun print(
        memo: PlaylistMemo,
        text: String,
    ) = memo.fingerprint(text.byteInputStream())

    private val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"News\",CBC News\nhttp://example.test/live/1.ts\n"

    @Test
    fun theSameFileAgainIsUnchanged() {
        val memo = memo()
        memo.remember(print(memo, playlist))

        assertTrue(memo.unchanged(print(memo, playlist), cached = true))
    }

    @Test
    fun aChangedFileIsSavedAgain() {
        val memo = memo()
        memo.remember(print(memo, playlist))

        assertFalse(memo.unchanged(print(memo, playlist.replace("CBC News", "CTV News")), cached = true))
    }

    @Test
    fun anEmptyCacheIsAlwaysFilled() {
        val memo = memo()
        memo.remember(print(memo, playlist))

        assertFalse(memo.unchanged(print(memo, playlist), cached = false))
    }

    @Test
    fun nothingRememberedOrForgottenMeansSave() {
        val memo = memo()
        assertFalse(memo.unchanged(print(memo, playlist), cached = true))

        memo.remember(print(memo, playlist))
        memo.forget()
        assertFalse(memo.unchanged(print(memo, playlist), cached = true))
    }

    @Test
    fun aNewAppVersionReadsTheFileOnceMore() {
        val old = memo(salt = "1004000")
        old.remember(print(old, playlist))
        val updated = memo(salt = "1005000")

        assertNotEquals(print(old, playlist), print(updated, playlist))
        assertFalse(updated.unchanged(print(updated, playlist), cached = true))
    }

    @Test
    fun aFileLargerThanOneBufferIsReadWhole() {
        val memo = memo()
        val big = playlist.repeat(10_000)
        assertEquals(print(memo, big), print(memo, big))
        assertNotEquals(print(memo, big), print(memo, big + "x"))
    }
}
