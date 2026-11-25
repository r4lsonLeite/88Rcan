package com.rcan88.core.media

data class SpsInfo(val profileIdc: Int, val levelIdc: Int, val width: Int, val height: Int)

/** Lê largura/altura de um SPS H.264 (ITU-T H.264 7.3.2.1.1). */
object SpsParser {

    fun parse(sps: ByteArray): SpsInfo {
        val r = BitReader(removeEmulationPrevention(sps))
        r.skip(8) // nal header
        val profileIdc = r.bits(8)
        r.skip(8) // constraint flags
        val levelIdc = r.bits(8)
        r.ue() // seq_parameter_set_id
        var chromaFormatIdc = 1
        var separateColourPlane = 0
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = r.ue()
            if (chromaFormatIdc == 3) separateColourPlane = r.bits(1)
            r.ue() // bit_depth_luma_minus8
            r.ue() // bit_depth_chroma_minus8
            r.skip(1) // qpprime_y_zero_transform_bypass_flag
            if (r.bits(1) == 1) { // seq_scaling_matrix_present_flag
                val count = if (chromaFormatIdc != 3) 8 else 12
                for (i in 0 until count) {
                    if (r.bits(1) == 1) skipScalingList(r, if (i < 6) 16 else 64)
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) { // pic_order_cnt_type
            0 -> r.ue()
            1 -> {
                r.skip(1)
                r.se(); r.se()
                val n = r.ue()
                repeat(n) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.skip(1) // gaps_in_frame_num_value_allowed_flag
        val widthMbs = r.ue() + 1
        val heightMapUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.skip(1) // mb_adaptive_frame_field_flag
        r.skip(1) // direct_8x8_inference_flag
        var cropL = 0; var cropR = 0; var cropT = 0; var cropB = 0
        if (r.bits(1) == 1) {
            cropL = r.ue(); cropR = r.ue(); cropT = r.ue(); cropB = r.ue()
        }
        val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormatIdc
        val cropUnitX = if (chromaArrayType == 0) 1 else if (chromaFormatIdc == 3) 1 else 2
        val subHeightC = if (chromaFormatIdc == 1) 2 else 1
        val cropUnitY = (if (chromaArrayType == 0) 1 else subHeightC) * (2 - frameMbsOnly)
        val width = widthMbs * 16 - cropUnitX * (cropL + cropR)
        val height = (2 - frameMbsOnly) * heightMapUnits * 16 - cropUnitY * (cropT + cropB)
        return SpsInfo(profileIdc, levelIdc, width, height)
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun skipScalingList(r: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (j in 0 until size) {
            if (next != 0) {
                val delta = r.se()
                next = (last + delta + 256) % 256
            }
            last = if (next == 0) last else next
        }
    }

    fun removeEmulationPrevention(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size)
        var zeros = 0
        for (b in data) {
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

    internal class BitReader(private val data: ByteArray) {
        private var pos = 0

        fun bits(n: Int): Int {
            var v = 0
            repeat(n) {
                val byte = data.getOrElse(pos shr 3) { throw IllegalArgumentException("SPS truncado") }.toInt()
                v = (v shl 1) or ((byte shr (7 - (pos and 7))) and 1)
                pos++
            }
            return v
        }

        fun skip(n: Int) { pos += n }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) {
                zeros++
                require(zeros < 32) { "Exp-Golomb inválido" }
            }
            return ((1 shl zeros) - 1) + if (zeros > 0) bits(zeros) else 0
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }
}

data class HevcSpsInfo(
    val profileIdc: Int,
    val levelIdc: Int,
    val width: Int,
    val height: Int,
    val chromaFormatIdc: Int,
    val bitDepthLumaMinus8: Int,
    val bitDepthChromaMinus8: Int,
    val maxSubLayersMinus1: Int,
    val temporalIdNesting: Int,
    /** 12 bytes do general_profile_tier_level (perfil, tier, flags, nível) — vão direto no hvcC. */
    val generalPtl: ByteArray,
)

/** Lê os campos do SPS H.265 (ITU-T H.265 7.3.2.2) necessários para o hvcC e o painel. */
object HevcSpsParser {
    fun parse(sps: ByteArray): HevcSpsInfo {
        val rbsp = SpsParser.removeEmulationPrevention(sps)
        val r = SpsParser.BitReader(rbsp)
        r.skip(16) // nal header
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.bits(3)
        val nesting = r.bits(1)
        val ptl = rbsp.copyOfRange(3, 15)
        r.skip(8) // profile_space, tier, profile_idc
        val profileIdc = ptl[0].toInt() and 0x1F
        r.skip(32 + 48)
        val levelIdc = r.bits(8)
        val subProfile = BooleanArray(maxSubLayersMinus1)
        val subLevel = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfile[i] = r.bits(1) == 1
            subLevel[i] = r.bits(1) == 1
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i]) r.skip(88)
            if (subLevel[i]) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        var width = r.ue()
        var height = r.ue()
        if (r.bits(1) == 1) { // conformance_window_flag
            val subW = if (chroma == 1 || chroma == 2) 2 else 1
            val subH = if (chroma == 1) 2 else 1
            val l = r.ue(); val rt = r.ue(); val t = r.ue(); val b = r.ue()
            width -= subW * (l + rt)
            height -= subH * (t + b)
        }
        val bdl = r.ue()
        val bdc = r.ue()
        return HevcSpsInfo(profileIdc, levelIdc, width, height, chroma, bdl, bdc, maxSubLayersMinus1, nesting, ptl)
    }
}
