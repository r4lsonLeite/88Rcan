package com.rcan88.core.rtmp

import com.rcan88.core.media.AudioConfig
import com.rcan88.core.media.VideoConfig
import java.io.ByteArrayOutputStream

/** Monta os corpos das tags FLV que trafegam dentro das mensagens RTMP de áudio/vídeo. */
object FlvTags {

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

    // 0xAF = AAC, 44kHz, 16 bits, estéreo (valores fixos por especificação para AAC)
    fun aacSequenceHeader(cfg: AudioConfig): ByteArray = byteArrayOf(0xAF.toByte(), 0x00) + cfg.asc

    fun aacFrame(data: ByteArray): ByteArray = byteArrayOf(0xAF.toByte(), 0x01) + data

    fun metadata(video: VideoConfig, audio: AudioConfig?): ByteArray {
        val m = LinkedHashMap<String, Any?>()
        video.info?.let { m["width"] = it.width; m["height"] = it.height }
        m["videocodecid"] = 7
        if (audio != null) {
            m["audiocodecid"] = 10
            m["audiosamplerate"] = audio.sampleRate
            m["stereo"] = audio.channels >= 2
        }
        m["encoder"] = "88Rcan"
        return Amf0.encode("@setDataFrame", "onMetaData", Amf0.EcmaArray(m))
    }
}
