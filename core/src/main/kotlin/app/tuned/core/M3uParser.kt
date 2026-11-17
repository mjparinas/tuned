package app.tuned.core

import java.io.BufferedReader

/**
 * Streams an extended M3U playlist (`#EXTM3U` / `#EXTINF`) one entry at a time, so a playlist with a hundred
 * thousand streams never sits in memory as a whole. Unknown tags are skipped; a broken line never stops the parse.
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
        var pending: ExtInf? = null
        var extGroup: String? = null
        var count = 0
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            when {
                line.isEmpty() -> {
                    Unit
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending = parseExtInf(line)
                    extGroup = null
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substring("#EXTGRP:".length).trim()
                }

                line.startsWith("#") -> {
                    Unit
                }

                else -> {
                    val info = pending ?: ExtInf(title = line.substringAfterLast('/'), attributes = emptyMap())
                    yield(toEntry(info, extGroup, line, count))
                    count++
                    pending = null
                    extGroup = null
                }
            }
        }
    }

    internal data class ExtInf(
        val title: String,
        val attributes: Map<String, String>,
    )

    /** Reads `#EXTINF:-1 key="value" key2="value2",Title`. Values may hold commas, so this scans, not splits. */
    @Suppress("CyclomaticComplexMethod") // a hand-written scanner reads best as one loop
    internal fun parseExtInf(line: String): ExtInf {
        val attributes = HashMap<String, String>()
        var i = line.indexOf(':').let { if (it < 0) line.length else it + 1 }
        // The duration: everything up to the first space or comma.
        while (i < line.length && line[i] != ' ' && line[i] != ',') i++
        while (i < line.length) {
            val c = line[i]
            when {
                c == ',' -> {
                    return ExtInf(line.substring(i + 1).trim(), attributes)
                }

                c == ' ' || c == '\t' -> {
                    i++
                }

                else -> {
                    val eq = line.indexOf('=', i)
                    if (eq < 0) break
                    val key = line.substring(i, eq).trim().lowercase()
                    if (eq + 1 < line.length && line[eq + 1] == '"') {
                        val close = line.indexOf('"', eq + 2)
                        if (close < 0) break
                        attributes[key] = line.substring(eq + 2, close).trim()
                        i = close + 1
                    } else {
                        var end = eq + 1
                        while (end < line.length && line[end] != ' ' && line[end] != ',') end++
                        attributes[key] = line.substring(eq + 1, end).trim()
                        i = end
                    }
                }
            }
        }
        // No comma after the attributes: the title is whatever tvg-name says.
        return ExtInf(attributes["tvg-name"].orEmpty(), attributes)
    }

    private fun toEntry(
        info: ExtInf,
        extGroup: String?,
        url: String,
        order: Int,
    ): PlaylistEntry {
        val a = info.attributes
        val name = info.title.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { url.substringAfterLast('/') }
        val group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup?.takeIf { it.isNotBlank() } ?: "Uncategorized"
        val kind = Classifier.kind(url, group)
        return PlaylistEntry(
            name = name,
            url = url,
            kind = kind,
            group = group,
            tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
            logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
            region = Regions.detect(group, a["tvg-country"], name),
            adult = Classifier.isAdult(group, name),
            episode = if (kind == ContentKind.SERIES) Episodes.parse(name, group) else null,
            order = order,
        )
    }
}
