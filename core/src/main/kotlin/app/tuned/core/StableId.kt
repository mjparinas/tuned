package app.tuned.core

/**
 * A stream's id, stable across refreshes so favourites and resume points survive. Xtream URLs end in the stream id
 * (`/live/user/pass/1234.ts`); that, the section and the name identify a stream even if the login changes.
 */
object StableId {
    fun of(entry: PlaylistEntry): Long {
        val tail =
            entry.url
                .substringBefore('?')
                .substringAfterLast('/')
                .substringBeforeLast('.')
        return fnv64("${entry.kind.code}|$tail|${entry.name}")
    }

    fun fnv64(text: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }
}
