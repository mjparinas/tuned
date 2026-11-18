package app.tuned.core

import org.xmlpull.v1.XmlPullParser
import java.util.Calendar
import java.util.TimeZone

/**
 * Streams an XMLTV guide. Guides run to hundreds of megabytes, so programmes go straight to [onProgramme] and
 * only those that pass [keep] (a channel the playlist has, inside the days the user keeps) cost anything.
 */
object XmltvParser {
    fun parse(
        parser: XmlPullParser,
        keep: (channelId: String, start: Long, stop: Long) -> Boolean,
        onProgramme: (Programme) -> Unit,
    ): Int {
        var count = 0
        for (programme in programmes(parser, keep)) {
            onProgramme(programme)
            count++
        }
        return count
    }

    /** The guide as a lazy sequence, so a caller can suspend (insert a batch) between programmes. */
    fun programmes(
        parser: XmlPullParser,
        keep: (channelId: String, start: Long, stop: Long) -> Boolean,
        /** Each `<channel>` and its first display name, for picking a guide entry by hand. */
        onChannel: (id: String, name: String) -> Unit = { _, _ -> },
    ): Sequence<Programme> = sequence {
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "channel") {
                val id = parser.getAttributeValue(null, "id")
                val name = readFirst(parser, "display-name")
                if (!id.isNullOrBlank()) onChannel(id, name?.trim()?.ifBlank { null } ?: id)
            } else if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")
                val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
                val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime)
                if (channel != null && start != null && stop != null && stop > start && keep(channel, start, stop)) {
                    val (title, desc) = readProgramme(parser)
                    yield(Programme(channel, start, stop, title.ifBlank { "Untitled" }, desc?.ifBlank { null }))
                } else {
                    skip(parser)
                }
            }
            event = parser.next()
        }
    }

    /** Reads `<title>` and `<desc>` (the first of each, whatever the language) and stops at `</programme>`. */
    private fun readProgramme(parser: XmlPullParser): Pair<String, String?> {
        var title: String? = null
        var desc: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> if (title == null) title = readText(parser).also { depth-- }
                        "desc" -> if (desc == null) desc = readText(parser).also { depth-- }
                    }
                }

                XmlPullParser.END_TAG -> {
                    depth--
                }

                XmlPullParser.END_DOCUMENT -> {
                    depth = 0
                }
            }
        }
        return (title?.trim() ?: "") to desc?.trim()
    }

    /** The text of the first [tag] inside the current element; stops at its end tag. */
    private fun readFirst(
        parser: XmlPullParser,
        tag: String,
    ): String? {
        var found: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == tag && found == null) found = readText(parser).also { depth-- }
                }

                XmlPullParser.END_TAG -> {
                    depth--
                }

                XmlPullParser.END_DOCUMENT -> {
                    depth = 0
                }
            }
        }
        return found
    }

    private fun readText(parser: XmlPullParser): String {
        val text = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return text.toString()
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
    }

    /** XMLTV times: `20261117050000 +0000`, the offset optional (then UTC), seconds optional. */
    fun parseTime(value: String): Long? {
        val v = value.trim()
        if (v.length < 12 || !v.substring(0, 12).all { it.isDigit() }) return null
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(
            v.substring(0, 4).toInt(),
            v.substring(4, 6).toInt() - 1,
            v.substring(6, 8).toInt(),
            v.substring(8, 10).toInt(),
            v.substring(10, 12).toInt(),
            if (v.length >= 14 && v[12].isDigit() && v[13].isDigit()) v.substring(12, 14).toInt() else 0,
        )
        var millis = calendar.timeInMillis
        val offset = v.substringAfter(' ', "").trim()
        if (offset.length == 5 && (offset[0] == '+' || offset[0] == '-') && offset.substring(1).all { it.isDigit() }) {
            val minutes = offset.substring(1, 3).toInt() * 60 + offset.substring(3, 5).toInt()
            millis -= (if (offset[0] == '+') 1 else -1) * minutes * 60_000L
        }
        return millis
    }
}
