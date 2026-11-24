package com.rcan88.core.media

/** Configuração do vídeo H.264 (parameter sets). */
class VideoConfig(val sps: ByteArray, val pps: ByteArray) {
    val info: SpsInfo? = runCatching { SpsParser.parse(sps) }.getOrNull()

    fun sameAs(other: VideoConfig?): Boolean =
        other != null && sps.contentEquals(other.sps) && pps.contentEquals(other.pps)

    override fun toString(): String =
        info?.let { "H.264 ${it.width}x${it.height} (profile ${it.profileIdc}, level ${it.levelIdc})" } ?: "H.264"
}

/** Configuração do áudio AAC (AudioSpecificConfig). */
class AudioConfig(val asc: ByteArray) {
    val objectType: Int
    val sampleRate: Int
    val channels: Int

    init {
        require(asc.size >= 2) { "AudioSpecificConfig muito curto" }
        val b0 = asc[0].toInt() and 0xFF
        val b1 = asc[1].toInt() and 0xFF
        objectType = b0 shr 3
        val freqIndex = ((b0 and 0x07) shl 1) or (b1 shr 7)
        sampleRate = SAMPLE_RATES.getOrElse(freqIndex) { 44100 }
        channels = (b1 shr 3) and 0x0F
    }

    override fun toString(): String = "AAC ${sampleRate}Hz ${channels}ch"

    companion object {
        val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
    }
}

/** Um access unit de vídeo: NAL units sem start code, com PTS em milissegundos. */
class VideoFrame(val nalus: List<ByteArray>, val ptsMs: Long, val keyframe: Boolean) {
    val size: Int get() = nalus.sumOf { it.size }
}

/** Um frame AAC cru (sem ADTS), com PTS em milissegundos. */
class AudioFrame(val data: ByteArray, val ptsMs: Long)

object H264 {
    const val NAL_SLICE = 1
    const val NAL_IDR = 5
    const val NAL_SEI = 6
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun nalType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F
}
