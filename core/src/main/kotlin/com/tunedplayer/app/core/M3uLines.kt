package com.tunedplayer.app.core

/**
 * Reads an extended M3U playlist one line at a time: hand it every line in order and it answers with a stream once
 * its link line arrives. Plain Kotlin, so Android ([M3uParser]) and the TV apps (tv/core, fed from a download as it
 * arrives) read playlists exactly the same way. Unknown tags are skipped; a broken line never stops the parse.
 */
class M3uLines {
    private var pending: ExtInf? = null
    private var extGroup: String? = null
    private val headers = LinkedHashMap<String, String>()

    // The header's attributes (tvg-shift, catch-up), for channels that don't give their own.
    private var header: Map<String, String> = emptyMap()

    /** How many streams have been read so far. */
    var count = 0
        private set

    /** Reads one line; the stream it ends, or null. */
    fun line(raw: String): PlaylistEntry? {
        val line = raw.trim()
        when {
            line.isEmpty() -> {
                Unit
            }

            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                header = parseExtInf("#EXTM3U:0 " + line.substring("#EXTM3U".length)).attributes
            }

            line.startsWith("#EXTINF", ignoreCase = true) -> {
                pending = parseExtInf(line)
                extGroup = null
                headers.clear()
            }

            line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                StreamLink.vlcOption(line)?.let { (name, value) -> headers[name] = value }
            }

            line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                extGroup = line.substring("#EXTGRP:".length).trim()
            }

            line.startsWith("#") -> {
                Unit
            }

            // Only a link is a stream. A server that won't send the playlist often answers with an HTML page
            // ("Access denied"); its lines must read as an empty playlist, not as streams called "<html>".
            "://" !in line -> {
                Unit
            }

            else -> {
                // Headers from #EXTVLCOPT lines and from Kodi's "link|User-Agent=…" form, kept in Kodi's form.
                val parts = StreamLink.split(line)
                val link = StreamLink.join(parts.url, headers + parts.headers)
                val info = pending ?: ExtInf(title = parts.url.substringAfterLast('/'), attributes = emptyMap())
                val entry = toEntry(info, extGroup, link, count, header)
                count++
                pending = null
                extGroup = null
                headers.clear()
                return entry
            }
        }
        return null
    }

    internal data class ExtInf(
        val title: String,
        val attributes: Map<String, String>,
    )

    companion object {
        /** Reads `#EXTINF:-1 key="value" key2='value2',Title`. Values may hold commas, so this scans, not splits. */
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
                        val quote = line.getOrNull(eq + 1)
                        if (quote == '"' || quote == '\'') {
                            val close = line.indexOf(quote, eq + 2)
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
            header: Map<String, String>,
        ): PlaylistEntry {
            val a = info.attributes
            val name = info.title.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { StreamLink.address(url).substringAfterLast('/') }
            val group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup?.takeIf { it.isNotBlank() } ?: "Uncategorized"
            val kind = Classifier.kind(url, group)
            return PlaylistEntry(
                name = name,
                url = url,
                kind = kind,
                group = group,
                tvgId = GuideShift.tag(a["tvg-id"]?.takeIf { it.isNotBlank() }, a["tvg-shift"] ?: header["tvg-shift"]),
                logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
                region = Regions.detect(group, a["tvg-country"], name, a["tvg-language"]),
                adult = Classifier.isAdult(group, name),
                episode = if (kind == ContentKind.SERIES) Episodes.parse(name, group) else null,
                order = order,
                catchUp = if (kind == ContentKind.LIVE) M3uCatchUp.from(a, header) else null,
            )
        }
    }
}
