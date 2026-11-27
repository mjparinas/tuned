package com.tunedplayer.app.core

/**
 * A stream's id, stable across refreshes so favourites and resume points survive. Xtream URLs end in the stream id
 * (`/live/user/pass/1234.ts`); that, the section and the name identify a stream even if the login changes.
 */
object StableId {
    fun of(entry: PlaylistEntry): Long {
        val tail =
            StreamLink
                .address(entry.url)
                .substringBefore('?')
                .substringAfterLast('/')
                .substringBeforeLast('.')
        return fnv64("${entry.kind.code}|$tail|${entry.name}")
    }

    /**
     * A whole show's id, for a favourite that is the show itself rather than one of its episodes (Melvin, 2026-11-27:
     * "I'm talking about the actual show itself"). [showKey] is [Episodes.showKey], the show's name normalized, so the
     * same show has the same id on every device and after every refresh. It sits among item ids in the favourites
     * table and the backup; the "show|" prefix keeps it from ever being a stream's id.
     */
    fun show(showKey: String): Long = fnv64("show|$showKey")

    fun fnv64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
        for (byte in text.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
