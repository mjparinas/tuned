package app.tuned.core

/**
 * Where MPEG-TS packets start in a chunk read from the middle of a stream. A recording begins wherever the person
 * pressed Record, usually partway through a packet; starting the file on a packet boundary means every player opens it
 * cleanly instead of skipping a broken first packet.
 */
object TsAlign {
    const val PACKET = 188
    private const val SYNC = 0x47.toByte()

    /** How many packets in a row must line up before a 0x47 byte counts as a packet start. */
    private const val CHECKS = 3

    /**
     * The first offset in [data] from [offset] (within [length] bytes) where a packet starts, judged by sync bytes
     * repeating every [PACKET] bytes, or -1 when none can be confirmed in this chunk.
     */
    fun firstPacket(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Int {
        val end = offset + length
        for (i in offset until minOf(end, offset + PACKET)) {
            if (data[i] != SYNC) continue
            val confirmed = (1..CHECKS).all { k -> i + k * PACKET >= end || data[i + k * PACKET] == SYNC }
            if (confirmed && i + PACKET < end) return i
        }
        return -1
    }
}
