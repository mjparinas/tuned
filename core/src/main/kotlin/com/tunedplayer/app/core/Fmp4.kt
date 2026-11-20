package com.tunedplayer.app.core

import java.io.ByteArrayOutputStream

/**
 * Fragmented MP4 (CMAF) for casting a saved file as HLS made on the fly ([CastHls]): one init segment with the
 * tracks' setup, then a few seconds of pictures and sound per piece, each written only when the TV asks for it.
 * A Chromecast plays H.264 and HEVC this way, which a plain MP4 of the same length can't give it piece by piece.
 */
object Fmp4 {
    /** One track of the stream. [entry] is its sample entry box ([avc1], [hvc1] or [mp4a]). */
    class Track(
        val id: Int,
        val video: Boolean,
        val timescale: Int,
        val entry: ByteArray,
        val width: Int = 0,
        val height: Int = 0,
    )

    /** One picture or sound frame. Video data is units after 4-byte lengths ([NalUnits.toLengthPrefixed]). */
    class Sample(
        val data: ByteArray,
        val timeUs: Long,
        val key: Boolean,
    )

    /** The `ftyp` and `moov` boxes that every piece refers to. */
    fun init(tracks: List<Track>): ByteArray = concat(
        box("ftyp") {
            ascii("iso6")
            u32(0)
            listOf("iso6", "cmfc", "isom", "mp41").forEach { ascii(it) }
        },
        box("moov") {
            bytes(
                fullBox("mvhd", 0, 0) {
                    u32(0) // creation
                    u32(0) // modification
                    u32(MOVIE_TIMESCALE.toLong())
                    u32(0) // duration: unknown, each piece says its own
                    u32(0x00010000) // rate 1.0
                    u16(0x0100) // volume 1.0
                    zeros(10)
                    matrix()
                    zeros(24)
                    u32((tracks.maxOf { it.id } + 1).toLong())
                },
            )
            tracks.forEach { bytes(trak(it)) }
            bytes(
                box("mvex") {
                    tracks.forEach { t ->
                        bytes(
                            fullBox("trex", 0, 0) {
                                u32(t.id.toLong())
                                u32(1) // sample description
                                u32(0)
                                u32(0)
                                u32(0)
                            },
                        )
                    }
                },
            )
        },
    )

    private fun trak(t: Track): ByteArray = box("trak") {
        bytes(
            fullBox("tkhd", 0, TRACK_ENABLED_IN_MOVIE) {
                u32(0)
                u32(0)
                u32(t.id.toLong())
                u32(0)
                u32(0) // duration
                zeros(8)
                u16(0) // layer
                u16(0) // alternate group
                u16(if (t.video) 0 else 0x0100)
                u16(0)
                matrix()
                u32(t.width.toLong() shl 16)
                u32(t.height.toLong() shl 16)
            },
        )
        bytes(
            box("mdia") {
                bytes(
                    fullBox("mdhd", 0, 0) {
                        u32(0)
                        u32(0)
                        u32(t.timescale.toLong())
                        u32(0)
                        u16(LANGUAGE_UND)
                        u16(0)
                    },
                )
                bytes(
                    fullBox("hdlr", 0, 0) {
                        u32(0)
                        ascii(if (t.video) "vide" else "soun")
                        zeros(12)
                        ascii(if (t.video) "Video" else "Sound")
                        u8(0)
                    },
                )
                bytes(minf(t))
            },
        )
    }

    private fun minf(t: Track): ByteArray = box("minf") {
        bytes(
            if (t.video) {
                fullBox("vmhd", 0, 1) { zeros(8) }
            } else {
                fullBox("smhd", 0, 0) { zeros(4) }
            },
        )
        bytes(
            box("dinf") {
                bytes(
                    fullBox("dref", 0, 0) {
                        u32(1)
                        bytes(fullBox("url ", 0, 1) {})
                    },
                )
            },
        )
        bytes(
            box("stbl") {
                bytes(
                    fullBox("stsd", 0, 0) {
                        u32(1)
                        bytes(t.entry)
                    },
                )
                bytes(fullBox("stts", 0, 0) { u32(0) })
                bytes(fullBox("stsc", 0, 0) { u32(0) })
                bytes(
                    fullBox("stsz", 0, 0) {
                        u32(0)
                        u32(0)
                    },
                )
                bytes(fullBox("stco", 0, 0) { u32(0) })
            },
        )
    }

    /** An H.264 sample entry around an `avcC` body ([NalUnits.avcC]). */
    fun avc1(
        width: Int,
        height: Int,
        avcC: ByteArray,
    ): ByteArray = visualEntry("avc1", width, height, box("avcC") { bytes(avcC) })

    /** An HEVC sample entry around an `hvcC` body ([NalUnits.hvcC]). */
    fun hvc1(
        width: Int,
        height: Int,
        hvcC: ByteArray,
    ): ByteArray = visualEntry("hvc1", width, height, box("hvcC") { bytes(hvcC) })

    private fun visualEntry(
        type: String,
        width: Int,
        height: Int,
        config: ByteArray,
    ): ByteArray = box(type) {
        zeros(6)
        u16(1) // data reference
        zeros(16)
        u16(width)
        u16(height)
        u32(DPI_72)
        u32(DPI_72)
        u32(0)
        u16(1) // frames per sample
        zeros(32) // compressor name
        u16(0x0018) // depth
        u16(0xFFFF)
        bytes(config)
    }

    /** An AAC sample entry for [config], the AudioSpecificConfig Android gives as the encoder's `csd-0`. */
    fun mp4a(
        channels: Int,
        sampleRate: Int,
        config: ByteArray,
    ): ByteArray = box("mp4a") {
        zeros(6)
        u16(1) // data reference
        zeros(8)
        u16(channels)
        u16(16) // sample size
        u16(0)
        u16(0)
        u32(sampleRate.toLong() shl 16)
        bytes(
            fullBox("esds", 0, 0) {
                val specific = descriptor(TAG_SPECIFIC, config)
                val decoder =
                    descriptor(
                        TAG_DECODER,
                        concat(
                            byteArrayOf(OBJECT_AAC, STREAM_AUDIO, 0, 0, 0),
                            ByteArray(8), // max and average bit rate: unknown
                            specific,
                        ),
                    )
                bytes(descriptor(TAG_ES, concat(byteArrayOf(0, 0, 0), decoder, descriptor(TAG_SL, byteArrayOf(2)))))
            },
        )
    }

    /**
     * One piece: a `moof` and its `mdat`, the samples of each track in decode order. [endUs] is where the next piece
     * starts, which gives the last picture its length.
     */
    fun segment(
        sequence: Int,
        runs: List<Pair<Track, List<Sample>>>,
        endUs: Long,
    ): ByteArray {
        val timed = runs.filter { it.second.isNotEmpty() }.map { (t, samples) -> t to timing(t, samples, endUs) }
        // The moof is written twice: once to learn its size, then with each run's offset into the mdat.
        val draft = moof(sequence, timed, IntArray(timed.size))
        var offset = draft.size + BOX_HEADER
        val offsets =
            IntArray(timed.size) { i ->
                val at = offset
                offset += timed[i].second.sizes.sum()
                at
            }
        val moof = moof(sequence, timed, offsets)
        val mdatSize = timed.sumOf { it.second.sizes.sum() }
        val out = ByteArrayOutputStream(moof.size + BOX_HEADER + mdatSize)
        out.write(moof)
        out.write(Writer().u32((BOX_HEADER + mdatSize).toLong()).ascii("mdat").toByteArray())
        timed.forEach { (_, timing) -> timing.data.forEach { out.write(it) } }
        return out.toByteArray()
    }

    /** Decode times, lengths and presentation offsets of one run, in the track's timescale. */
    private class Timing(
        val baseDecode: Long,
        val durations: LongArray,
        val sizes: IntArray,
        val flags: IntArray,
        val offsets: LongArray,
        val data: List<ByteArray>,
    )

    /**
     * Pictures arrive in decode order with only their presentation times (Android gives no decode times), so decode
     * times are the same times sorted, as CMAF writers do; a picture shown before one decoded earlier gets a negative
     * offset (`trun` version 1).
     */
    private fun timing(
        t: Track,
        samples: List<Sample>,
        endUs: Long,
    ): Timing {
        val presentation = LongArray(samples.size) { ticks(samples[it].timeUs, t.timescale) }
        val decode = if (t.video) presentation.sortedArray() else presentation
        val end = ticks(endUs, t.timescale)
        val durations =
            LongArray(samples.size) { i ->
                val next = if (i + 1 < decode.size) decode[i + 1] else maxOf(end, presentation.max() + 1)
                (next - decode[i]).coerceAtLeast(if (i + 1 < decode.size) 0 else 1)
            }
        if (!t.video) {
            // Sound frames are back to back; a gap in the times is a frame Android couldn't read.
            for (i in durations.indices) if (durations[i] <= 0 || i == durations.lastIndex) durations[i] = typical(durations)
        }
        return Timing(
            baseDecode = decode.first(),
            durations = durations,
            sizes = IntArray(samples.size) { samples[it].data.size },
            flags = IntArray(samples.size) { if (samples[it].key || !t.video) SYNC_FLAGS else NON_SYNC_FLAGS },
            offsets = LongArray(samples.size) { presentation[it] - decode[it] },
            data = samples.map { it.data },
        )
    }

    private fun typical(durations: LongArray): Long = durations
        .dropLast(1)
        .filter { it > 0 }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key ?: AAC_FRAME

    private fun moof(
        sequence: Int,
        timed: List<Pair<Track, Timing>>,
        offsets: IntArray,
    ): ByteArray = box("moof") {
        bytes(fullBox("mfhd", 0, 0) { u32(sequence.toLong()) })
        timed.forEachIndexed { i, (t, timing) ->
            bytes(
                box("traf") {
                    bytes(fullBox("tfhd", 0, DEFAULT_BASE_IS_MOOF) { u32(t.id.toLong()) })
                    bytes(fullBox("tfdt", 1, 0) { u64(timing.baseDecode) })
                    val flags = TRUN_DATA_OFFSET or TRUN_DURATION or TRUN_SIZE or TRUN_FLAGS or (if (t.video) TRUN_OFFSETS else 0)
                    bytes(
                        fullBox("trun", 1, flags) {
                            u32(timing.sizes.size.toLong())
                            u32(offsets[i].toLong())
                            for (n in timing.sizes.indices) {
                                u32(timing.durations[n])
                                u32(timing.sizes[n].toLong())
                                u32(timing.flags[n].toLong())
                                if (t.video) u32(timing.offsets[n] and 0xFFFFFFFFL)
                            }
                        },
                    )
                },
            )
        }
    }

    private fun ticks(
        us: Long,
        timescale: Int,
    ): Long = Math.floorDiv(us * timescale + US_PER_S / 2, US_PER_S)

    private fun descriptor(
        tag: Int,
        body: ByteArray,
    ): ByteArray {
        require(body.size < SHORT_DESCRIPTOR) { "Descriptor too long" }
        return concat(byteArrayOf(tag.toByte(), body.size.toByte()), body)
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun box(
        type: String,
        body: Writer.() -> Unit,
    ): ByteArray {
        val content = Writer().apply(body).toByteArray()
        return Writer()
            .u32((BOX_HEADER + content.size).toLong())
            .ascii(type)
            .bytes(content)
            .toByteArray()
    }

    private fun fullBox(
        type: String,
        version: Int,
        flags: Int,
        body: Writer.() -> Unit,
    ): ByteArray = box(type) {
        u32((version.toLong() shl 24) or flags.toLong())
        body()
    }

    /** Big-endian writing, as MP4 is. */
    private class Writer {
        private val out = ByteArrayOutputStream()

        fun u8(v: Int) = apply { out.write(v) }

        fun u16(v: Int) = apply {
            out.write(v ushr 8)
            out.write(v)
        }

        fun u32(v: Long) = apply {
            out.write((v ushr 24).toInt())
            out.write((v ushr 16).toInt())
            out.write((v ushr 8).toInt())
            out.write(v.toInt())
        }

        fun u64(v: Long) = apply {
            u32(v ushr 32)
            u32(v and 0xFFFFFFFFL)
        }

        fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.US_ASCII)) }

        fun bytes(b: ByteArray) = apply { out.write(b) }

        fun zeros(n: Int) = apply { out.write(ByteArray(n)) }

        fun matrix() = apply {
            listOf(0x00010000L, 0L, 0L, 0L, 0x00010000L, 0L, 0L, 0L, 0x40000000L).forEach { u32(it) }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private const val BOX_HEADER = 8
    private const val MOVIE_TIMESCALE = 1000
    private const val TRACK_ENABLED_IN_MOVIE = 3
    private const val LANGUAGE_UND = 0x55C4
    private const val DPI_72 = 0x00480000L
    private const val DEFAULT_BASE_IS_MOOF = 0x020000
    private const val TRUN_DATA_OFFSET = 0x1
    private const val TRUN_DURATION = 0x100
    private const val TRUN_SIZE = 0x200
    private const val TRUN_FLAGS = 0x400
    private const val TRUN_OFFSETS = 0x800
    private const val SYNC_FLAGS = 0x02000000
    private const val NON_SYNC_FLAGS = 0x01010000
    private const val AAC_FRAME = 1024L
    private const val US_PER_S = 1_000_000L
    private const val SHORT_DESCRIPTOR = 128
    private const val TAG_ES = 3
    private const val TAG_DECODER = 4
    private const val TAG_SPECIFIC = 5
    private const val TAG_SL = 6
    private const val OBJECT_AAC: Byte = 0x40
    private const val STREAM_AUDIO: Byte = 0x15
}
