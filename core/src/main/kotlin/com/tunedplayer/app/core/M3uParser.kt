package com.tunedplayer.app.core

import java.io.BufferedReader

/**
 * Streams an extended M3U playlist (`#EXTM3U` / `#EXTINF`) one entry at a time, so a playlist with a hundred
 * thousand streams never sits in memory as a whole. Unknown tags are skipped; a broken line never stops the parse.
 * The reading itself is [M3uLines], which the TV apps run too (tv/core).
 */
object M3uParser {
    /** Calls [onEntry] for every stream in [reader], in playlist order. Returns how many entries were read. */
    fun parse(
        reader: BufferedReader,
        onEntry: (PlaylistEntry) -> Unit,
    ): Int {
        var count = 0
        for (entry in entries(reader)) {
            onEntry(entry)
            count++
        }
        return count
    }

    /** The playlist as a lazy sequence, so a caller can suspend (insert a batch) between entries. */
    fun entries(reader: BufferedReader): Sequence<PlaylistEntry> = sequence {
        val lines = M3uLines()
        while (true) {
            val raw = reader.readLine() ?: break
            lines.line(raw)?.let { yield(it) }
        }
    }

    internal fun parseExtInf(line: String): M3uLines.ExtInf = M3uLines.parseExtInf(line)
}
