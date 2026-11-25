package com.rcan88.core.rtmp

import com.rcan88.core.media.AudioConfig
import com.rcan88.core.media.HevcSpsParser
import com.rcan88.core.media.VideoCodec
import com.rcan88.core.media.VideoConfig
import java.io.ByteArrayOutputStream

/** Monta os corpos das tags FLV que trafegam dentro das mensagens RTMP de áudio/vídeo. */
object FlvTags {

    /** Sequence header do codec, enviado antes do primeiro frame. */
    fun videoSequenceHeader(cfg: VideoConfig): ByteArray =
        if (cfg.codec == VideoCodec.H265) hevcSequenceHeader(cfg) else avcSequenceHeader(cfg)

    fun videoFrame(codec: VideoCodec, nalus: List<ByteArray>, keyframe: Boolean): ByteArray =
        if (codec == VideoCodec.H265) hevcFrame(nalus, keyframe) else avcFrame(nalus, keyframe)

    /** AVCDecoderConfigurationRecord (ISO 14496-15) — enviado antes do primeiro frame. */
    fun avcSequenceHeader(cfg: VideoConfig): ByteArray {
        val sps = cfg.sps
        val pps = cfg.pps
        val out = ByteArrayOutputStream(16 + sps.size + pps.size)
        out.write(0x17) // keyframe + AVC
        out.write(0x00) // AVC sequence header
        out.write(0); out.write(0); out.write(0) // composition time
        out.write(0x01) // configurationVersion
        out.write(sps[1].toInt()) // profile
        out.write(sps[2].toInt()) // compatibility
        out.write(sps[3].toInt()) // level
        out.write(0xFF) // lengthSizeMinusOne = 3 (NAL com prefixo de 4 bytes)
        out.write(0xE1) // 1 SPS
        out.write(sps.size shr 8); out.write(sps.size and 0xFF); out.write(sps)
        out.write(0x01) // 1 PPS
        out.write(pps.size shr 8); out.write(pps.size and 0xFF); out.write(pps)
        return out.toByteArray()
    }

    fun avcFrame(nalus: List<ByteArray>, keyframe: Boolean, compositionTimeMs: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(5 + nalus.sumOf { it.size + 4 })
        out.write(if (keyframe) 0x17 else 0x27)
        out.write(0x01) // AVC NALU
        out.write((compositionTimeMs shr 16) and 0xFF)
        out.write((compositionTimeMs shr 8) and 0xFF)
        out.write(compositionTimeMs and 0xFF)
        for (n in nalus) {
            out.write(n.size ushr 24); out.write((n.size shr 16) and 0xFF)
            out.write((n.size shr 8) and 0xFF); out.write(n.size and 0xFF)
            out.write(n)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- HEVC (Enhanced RTMP)
    // Byte 0 de vídeo no Enhanced RTMP: IsExHeader(1) | FrameType(3) | PacketType(4), seguido do FourCC.
    private val HVC1 = byteArrayOf('h'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), '1'.code.toByte())
    private const val PACKET_SEQUENCE_START = 0
    private const val PACKET_CODED_FRAMES = 1

    /** Valor numérico do FourCC 'hvc1', usado em videocodecid no onMetaData. */
    const val HVC1_CODEC_ID = 0x68766331

    /** HEVCDecoderConfigurationRecord (ISO 14496-15 8.3.3.1). */
    fun hevcSequenceHeader(cfg: VideoConfig): ByteArray {
        val vps = requireNotNull(cfg.vps) { "HEVC sem VPS" }
        val info = HevcSpsParser.parse(cfg.sps)
        val out = ByteArrayOutputStream(64 + vps.size + cfg.sps.size + cfg.pps.size)
        out.write(0x80 or (1 shl 4) or PACKET_SEQUENCE_START) // ex-header, keyframe
        out.write(HVC1)
        out.write(0x01) // configurationVersion
        out.write(info.generalPtl) // profile_space/tier/profile, compat flags, constraint flags, level
        out.write(0xF0); out.write(0x00) // min_spatial_segmentation_idc = 0
        out.write(0xFC) // parallelismType = 0
        out.write(0xFC or (info.chromaFormatIdc and 0x03))
        out.write(0xF8 or (info.bitDepthLumaMinus8 and 0x07))
        out.write(0xF8 or (info.bitDepthChromaMinus8 and 0x07))
        out.write(0); out.write(0) // avgFrameRate
        out.write(((info.maxSubLayersMinus1 + 1) shl 3) or (info.temporalIdNesting shl 2) or 0x03) // lengthSize 4
        out.write(3) // numOfArrays
        for ((type, nal) in listOf(32 to vps, 33 to cfg.sps, 34 to cfg.pps)) {
            out.write(0x80 or type) // array_completeness = 1
            out.write(0); out.write(1) // numNalus
            out.write(nal.size shr 8); out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    fun hevcFrame(nalus: List<ByteArray>, keyframe: Boolean): ByteArray {
        val out = ByteArrayOutputStream(8 + nalus.sumOf { it.size + 4 })
        out.write(0x80 or ((if (keyframe) 1 else 2) shl 4) or PACKET_CODED_FRAMES)
        out.write(HVC1)
        out.write(0); out.write(0); out.write(0) // composition time
        for (n in nalus) {
            out.write(n.size ushr 24); out.write((n.size shr 16) and 0xFF)
            out.write((n.size shr 8) and 0xFF); out.write(n.size and 0xFF)
            out.write(n)
        }
        return out.toByteArray()
    }

    // 0xAF = AAC, 44kHz, 16 bits, estéreo (valores fixos por especificação para AAC)
    fun aacSequenceHeader(cfg: AudioConfig): ByteArray = byteArrayOf(0xAF.toByte(), 0x00) + cfg.asc

    fun aacFrame(data: ByteArray): ByteArray = byteArrayOf(0xAF.toByte(), 0x01) + data

    fun metadata(video: VideoConfig, audio: AudioConfig?): ByteArray {
        val m = LinkedHashMap<String, Any?>()
        video.info?.let { m["width"] = it.width; m["height"] = it.height }
        m["videocodecid"] = if (video.codec == VideoCodec.H265) HVC1_CODEC_ID else 7
        if (audio != null) {
            m["audiocodecid"] = 10
            m["audiosamplerate"] = audio.sampleRate
            m["stereo"] = audio.channels >= 2
        }
        m["encoder"] = "88Rcan"
        return Amf0.encode("@setDataFrame", "onMetaData", Amf0.EcmaArray(m))
    }
}
