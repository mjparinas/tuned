package com.tunedplayer.app.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

object Streams {
    /** Guides are often served as .gz without a Content-Encoding header; unwrap them by their magic bytes. */
    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }
}
