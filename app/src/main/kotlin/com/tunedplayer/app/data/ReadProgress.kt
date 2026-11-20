package com.tunedplayer.app.data

import com.tunedplayer.app.core.Streams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * How far a refresh has read through a downloaded file, so the step after the download has a real bar: as the file is on
 * disk, so a gzipped file counts its compressed bytes, which still run from 0 to its size. A step that reads an API's
 * answer instead has no file, and counts only what it has found.
 */
class ReadProgress {
    @Volatile private var file: CountingInputStream? = null

    /** Opens [file] (unzipped when it is gzipped) for [read], counting its bytes; [step] is reported as it starts. */
    suspend fun <T> read(
        file: File,
        step: SyncProgress.Step,
        progress: (SyncProgress) -> Unit,
        read: suspend (InputStream) -> T,
    ): T = withContext(Dispatchers.IO) {
        CountingInputStream(file.inputStream(), file.length()).use { counted ->
            this@ReadProgress.file = counted
            try {
                progress(at(step, 0))
                read(Streams.maybeGunzip(counted))
            } finally {
                this@ReadProgress.file = null
            }
        }
    }

    /** [step] after [rows] channels or programmes: through the file when one is being read. */
    fun at(
        step: SyncProgress.Step,
        rows: Int,
    ): SyncProgress {
        val reading = file
        return SyncProgress(step, done = reading?.count ?: 0, total = reading?.size ?: -1, rows = rows.toLong())
    }
}

/** Counts the bytes read from [input], out of [size], for a step's progress while a downloaded file is read. */
class CountingInputStream(
    input: InputStream,
    /** The file's size in bytes. */
    val size: Long,
) : FilterInputStream(input) {
    /** Bytes read (or skipped) so far. */
    @Volatile var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }

    // A reset would move back to an unknown point, so marks aren't offered.
    override fun markSupported(): Boolean = false
}
