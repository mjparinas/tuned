package app.tuned.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Remembers which playlist file the cache was last saved from, so a refresh that downloads the very same bytes skips
 * saving them again (Melvin, 2026-11-18: "i see 'saving channels' a lot, why"). Saving a big playlist reads every
 * line into a side table and compares it with the cache, which takes minutes on a phone, while a file that hasn't
 * changed would change nothing. [salt] is the app's version, so a new parser still reads an old file once.
 */
class PlaylistMemo(
    private val file: File,
    private val salt: String,
) {
    /** A SHA-256 of [salt] and the file's bytes, read in one pass. */
    fun fingerprint(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when the cache holds channels and they were saved from a file with this [fingerprint]. */
    fun unchanged(
        fingerprint: String,
        cached: Boolean,
    ): Boolean = cached && file.exists() && file.readText() == fingerprint

    /** After the cache was saved from the file with this [fingerprint]. */
    fun remember(fingerprint: String) {
        file.parentFile?.mkdirs()
        file.writeText(fingerprint)
    }

    /** Before the cache is rewritten, or when it no longer comes from one file (the Xtream API, extra shows). */
    fun forget() {
        file.delete()
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}
