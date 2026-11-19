package com.tunedplayer.app.core

import java.util.Locale

/**
 * Subtitles for a Chromecast, whose player reads WebVTT only: a SubRip (`.srt`) file, as OpenSubtitles gives them,
 * turned into WebVTT, with the subtitle timing ([SubtitleOffset]) worked into each cue.
 */
object WebVtt {
    const val MIME = "text/vtt"

    /** [srt] as WebVTT, each cue moved by [offsetMs] (positive shows it later); cues moved before the start are left out. */
    fun fromSubRip(
        srt: String,
        offsetMs: Long = 0,
    ): String {
        val blocks =
            srt
                .removePrefix(BOM)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split(BLANK_LINES)
        return blocks.mapNotNull { cue(it, offsetMs) }.joinToString("", prefix = "WEBVTT\n\n")
    }

    /** One SubRip block as a WebVTT cue, or null when it has no timing or text, or ends before the start once moved. */
    private fun cue(
        block: String,
        offsetMs: Long,
    ): String? {
        val lines = block.lines().dropWhile { it.isBlank() }
        val timing = lines.indexOfFirst { ARROW in it }
        val (start, end) = lines.getOrNull(timing)?.let(::times) ?: return null
        val from = start + offsetMs
        val to = end + offsetMs
        val text = lines.drop(timing + 1).map(::clean).filter { it.isNotBlank() }
        if (to <= 0 || to <= from || text.isEmpty()) return null
        return stamp(from.coerceAtLeast(0)) + " --> " + stamp(to) + "\n" + text.joinToString("") { it + "\n" } + "\n"
    }

    /** The start and end of a SubRip timing line ("00:01:02,500 --> 00:01:04,000"), in ms. */
    private fun times(line: String): Pair<Long, Long>? {
        val parts = line.split(ARROW)
        if (parts.size != 2) return null
        val start = ms(parts[0].trim()) ?: return null
        val end = ms(parts[1].trim().substringBefore(' ')) ?: return null
        return start to end
    }

    private fun ms(time: String): Long? {
        val parts = TIME.matchEntire(time)?.groupValues ?: return null
        val millis = parts[4].padEnd(3, '0').take(3).toLong()
        return ((parts[1].toLong() * 60 + parts[2].toLong()) * 60 + parts[3].toLong()) * 1000 + millis
    }

    private fun stamp(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", h, m, s, ms % 1000)
    }

    /** Keeps the tags WebVTT knows (italic, bold, underline) and drops the rest, such as `<font>` and `{\an8}`. */
    private fun clean(line: String): String = line
        .replace(ASS_TAG, "")
        .replace(UNKNOWN_TAG, "")
        .replace(ARROW, "→")
        .trimEnd()

    private const val BOM = "﻿"
    private const val ARROW = "-->"
    private val BLANK_LINES = Regex("\n[ \t]*\n")
    private val TIME = Regex("""(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})""")
    private val ASS_TAG = Regex("""\{\\[^}]*\}""")
    private val UNKNOWN_TAG = Regex("""</?(?!(?:i|b|u)>)[a-zA-Z][^>]*>""")
}
