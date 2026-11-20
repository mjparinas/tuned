package com.tunedplayer.app.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * H.264 and HEVC pictures as an MP4 holds them, for casting a saved file as a stream made on the fly ([Fmp4]).
 * Android's MediaExtractor hands pictures over with start codes (Annex B) and the codec setup (SPS, PPS, VPS) as
 * `csd-0` and `csd-1`; an MP4 wants each unit after its 4-byte length and the setup in an `avcC` or `hvcC` box.
 */
object NalUnits {
    /** The units in [data], with start codes or 4-byte lengths in front of each. */
    fun split(data: ByteArray): List<ByteArray> = lengthPrefixed(data) ?: annexB(data)

    /** [data] with each unit after its 4-byte length, as an MP4 sample holds it. */
    fun toLengthPrefixed(data: ByteArray): ByteArray {
        if (lengthPrefixed(data) != null) return data
        val out = ByteArrayOutputStream(data.size + LENGTH_BYTES * 2)
        for (unit in annexB(data)) {
            out.write(unit.size ushr 24)
            out.write(unit.size ushr 16)
            out.write(unit.size ushr 8)
            out.write(unit.size)
            out.write(unit)
        }
        return out.toByteArray()
    }

    /** The units of [data] when it is a chain of 4-byte lengths that ends exactly at its end, else null. */
    private fun lengthPrefixed(data: ByteArray): List<ByteArray>? {
        val units = ArrayList<ByteArray>()
        var at = 0
        while (at < data.size) {
            if (at + LENGTH_BYTES > data.size) return null
            val size = ByteBuffer.wrap(data, at, LENGTH_BYTES).int.toLong() and 0xFFFFFFFFL
            if (size <= 0 || at + LENGTH_BYTES + size > data.size) return null
            units += data.copyOfRange(at + LENGTH_BYTES, at + LENGTH_BYTES + size.toInt())
            at += LENGTH_BYTES + size.toInt()
        }
        return units.takeIf { it.isNotEmpty() }
    }

    /** The units between start codes (00 00 01 or 00 00 00 01). Data without one is taken as a single unit. */
    private fun annexB(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>() // first byte after each start code
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return if (data.isEmpty()) emptyList() else listOf(data)
        return starts.mapIndexedNotNull { n, start ->
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
            // The zero in front of a 4-byte start code belongs to it, not to this unit.
            while (end > start && n + 1 < starts.size && data[end - 1].toInt() == 0) end--
            if (end > start) data.copyOfRange(start, end) else null
        }
    }

    /** The `avcC` box body (AVCDecoderConfigurationRecord) from the SPS and PPS, as Android gives them. */
    fun avcC(
        csd0: ByteArray,
        csd1: ByteArray?,
    ): ByteArray {
        val units = split(csd0) + (csd1?.let { split(it) } ?: emptyList())
        val sps = units.filter { it.isNotEmpty() && it[0].toInt() and AVC_TYPE_MASK == AVC_SPS }
        val pps = units.filter { it.isNotEmpty() && it[0].toInt() and AVC_TYPE_MASK == AVC_PPS }
        val first = requireNotNull(sps.firstOrNull()) { "No SPS in the video's setup" }
        require(first.size >= AVC_SPS_HEADER) { "The SPS is too short" }
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(first[1].toInt()) // profile
        out.write(first[2].toInt()) // compatibility
        out.write(first[3].toInt()) // level
        out.write(0xFF) // 4-byte lengths
        out.write(0xE0 or sps.size)
        sps.forEach { out.writeUnit(it) }
        out.write(pps.size)
        pps.forEach { out.writeUnit(it) }
        return out.toByteArray()
    }

    /** What the `hvcC` box needs from an HEVC SPS. */
    class HevcSps(
        /** general_profile_space, general_tier_flag and general_profile_idc, as one byte, as `hvcC` holds them. */
        val profileByte: Int,
        val compatibility: Long,
        /** The 6 bytes of general_constraint_indicator_flags. */
        val constraints: ByteArray,
        val level: Int,
        val chromaFormat: Int,
        val lumaBitDepth: Int,
        val chromaBitDepth: Int,
        val temporalLayers: Int,
        val temporalIdNested: Boolean,
    ) {
        val profile: Int get() = profileByte and PROFILE_MASK
    }

    /** Reads the profile, level, chroma format and bit depth from an HEVC SPS unit (with its 2-byte header). */
    fun hevcSps(unit: ByteArray): HevcSps {
        val bits = Bits(unescape(unit), from = HEVC_HEADER_BYTES)
        bits.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = bits.read(3)
        val nested = bits.read(1) == 1
        val profileSpace = bits.read(2)
        val tier = bits.read(1)
        val profile = bits.read(5)
        val compatibility = bits.readLong(32)
        val constraints = ByteArray(6) { bits.read(8).toByte() }
        val level = bits.read(8)
        // Sub-layer profile and level, read only to get past them.
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = bits.read(1) == 1
            levelPresent[i] = bits.read(1) == 1
        }
        if (maxSubLayersMinus1 > 0) bits.skip(2 * (MAX_SUB_LAYERS - maxSubLayersMinus1))
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) bits.skip(SUB_LAYER_PROFILE_BITS)
            if (levelPresent[i]) bits.skip(8)
        }
        bits.readUe() // sps_seq_parameter_set_id
        val chromaFormat = bits.readUe()
        if (chromaFormat == 3) bits.skip(1) // separate_colour_plane_flag
        bits.readUe() // width
        bits.readUe() // height
        if (bits.read(1) == 1) repeat(4) { bits.readUe() } // conformance window
        val luma = bits.readUe() + 8
        val chroma = bits.readUe() + 8
        return HevcSps(
            profileByte = (profileSpace shl 6) or (tier shl 5) or profile,
            compatibility = compatibility,
            constraints = constraints,
            level = level,
            chromaFormat = chromaFormat,
            lumaBitDepth = luma,
            chromaBitDepth = chroma,
            temporalLayers = maxSubLayersMinus1 + 1,
            temporalIdNested = nested,
        )
    }

    /** The `hvcC` box body (HEVCDecoderConfigurationRecord) from the VPS, SPS and PPS, as Android gives them. */
    fun hvcC(
        csd0: ByteArray,
        csd1: ByteArray? = null,
    ): ByteArray {
        val units = split(csd0) + (csd1?.let { split(it) } ?: emptyList())
        val byType = units.filter { it.size >= HEVC_HEADER_BYTES }.groupBy { (it[0].toInt() ushr 1) and HEVC_TYPE_MASK }
        val sps = hevcSps(requireNotNull(byType[HEVC_SPS]?.first()) { "No SPS in the video's setup" })
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(sps.profileByte)
        out.writeInt(sps.compatibility)
        out.write(sps.constraints)
        out.write(sps.level)
        out.write(0xF0) // min_spatial_segmentation_idc: none
        out.write(0x00)
        out.write(0xFC) // parallelismType: unknown
        out.write(0xFC or sps.chromaFormat)
        out.write(0xF8 or (sps.lumaBitDepth - 8))
        out.write(0xF8 or (sps.chromaBitDepth - 8))
        out.write(0) // avgFrameRate: unknown
        out.write(0)
        out.write((sps.temporalLayers shl 3) or ((if (sps.temporalIdNested) 1 else 0) shl 2) or 3) // 4-byte lengths
        val arrays = listOf(HEVC_VPS, HEVC_SPS, HEVC_PPS).mapNotNull { type -> byType[type]?.let { type to it } }
        out.write(arrays.size)
        for ((type, list) in arrays) {
            out.write(0x80 or type) // array_completeness
            out.write(list.size ushr 8)
            out.write(list.size)
            list.forEach { out.writeUnit(it) }
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUnit(unit: ByteArray) {
        write(unit.size ushr 8)
        write(unit.size)
        write(unit)
    }

    private fun ByteArrayOutputStream.writeInt(v: Long) {
        write((v ushr 24).toInt())
        write((v ushr 16).toInt())
        write((v ushr 8).toInt())
        write(v.toInt())
    }

    /** [unit] without emulation prevention bytes (00 00 03 becomes 00 00). */
    private fun unescape(unit: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(unit.size)
        var zeros = 0
        for (b in unit) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Reads bits most significant first, and Exp-Golomb numbers. */
    private class Bits(
        private val data: ByteArray,
        from: Int,
    ) {
        private var bit = from * 8

        fun read(n: Int): Int = readLong(n).toInt()

        fun readLong(n: Int): Long {
            var v = 0L
            repeat(n) {
                val byte = bit / 8
                require(byte < data.size) { "The SPS ended early" }
                v = (v shl 1) or ((data[byte].toLong() ushr (7 - bit % 8)) and 1)
                bit++
            }
            return v
        }

        fun skip(n: Int) {
            bit += n
        }

        fun readUe(): Int {
            var zeros = 0
            while (read(1) == 0) {
                zeros++
                require(zeros < MAX_UE_ZEROS) { "A number in the SPS is too long" }
            }
            return ((1L shl zeros) - 1 + readLong(zeros)).toInt()
        }
    }

    private const val LENGTH_BYTES = 4
    private const val PROFILE_MASK = 0x1F
    private const val AVC_TYPE_MASK = 0x1F
    private const val AVC_SPS = 7
    private const val AVC_PPS = 8
    private const val AVC_SPS_HEADER = 4
    private const val HEVC_HEADER_BYTES = 2
    private const val HEVC_TYPE_MASK = 0x3F
    private const val HEVC_VPS = 32
    private const val HEVC_SPS = 33
    private const val HEVC_PPS = 34
    private const val MAX_SUB_LAYERS = 8
    private const val SUB_LAYER_PROFILE_BITS = 88
    private const val MAX_UE_ZEROS = 32
}
