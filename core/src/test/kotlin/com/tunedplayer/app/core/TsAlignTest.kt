package com.tunedplayer.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TsAlignTest {
    private fun stream(
        skip: Int,
        packets: Int,
    ): ByteArray {
        val whole = ByteArray(TsAlign.PACKET * packets)
        for (p in 0 until packets) whole[p * TsAlign.PACKET] = 0x47
        // A stray sync byte inside the first packet's payload must not be taken for a packet start.
        whole[5] = 0x47
        return whole.copyOfRange(skip, whole.size)
    }

    @Test
    fun alignedChunkStartsAtZero() {
        assertEquals(0, TsAlign.firstPacket(stream(0, 6)))
    }

    @Test
    fun midPacketChunkStartsAtTheNextPacket() {
        // 100 bytes into the first packet: the next starts 88 bytes in, and the stray 0x47 at 5 - 100 is gone.
        assertEquals(TsAlign.PACKET - 100, TsAlign.firstPacket(stream(100, 6)))
    }

    @Test
    fun strayPayloadSyncByteIsSkipped() {
        // Starting 2 bytes in, the stray byte at index 3 comes first but doesn't repeat every 188 bytes.
        assertEquals(TsAlign.PACKET - 2, TsAlign.firstPacket(stream(2, 6)))
    }

    @Test
    fun respectsOffset() {
        val data = stream(0, 6)
        assertEquals(TsAlign.PACKET, TsAlign.firstPacket(data, offset = 1, length = data.size - 1))
    }

    @Test
    fun noPacketsInNoise() {
        assertEquals(-1, TsAlign.firstPacket(ByteArray(1_000)))
    }

    @Test
    fun tooShortToConfirm() {
        assertEquals(-1, TsAlign.firstPacket(stream(0, 6).copyOf(100)))
    }
}
