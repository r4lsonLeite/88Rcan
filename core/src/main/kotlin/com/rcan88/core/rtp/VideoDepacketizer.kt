package com.rcan88.core.rtp

import com.rcan88.core.media.H264
import com.rcan88.core.media.H265
import java.io.ByteArrayOutputStream

enum class ParamSet { VPS, SPS, PPS }

/**
 * Base comum para remontar access units de vídeo a partir de RTP.
 *
 * Em caso de perda de pacote o access unit corrompido é descartado e o fluxo
 * só é retomado no próximo keyframe — evita artefatos na live.
 */
abstract class VideoDepacketizer(
    private val onParameterSet: (kind: ParamSet, nal: ByteArray) -> Unit,
    private val onAccessUnit: (nalus: List<ByteArray>, rtpTimestamp: Long, keyframe: Boolean) -> Unit,
) {
    private val unwrapper = TimestampUnwrapper()
    private val nalus = ArrayList<ByteArray>()
    private var currentTs = Long.MIN_VALUE
    private var lastSeq = -1
    protected var fuBuffer: ByteArrayOutputStream? = null
    protected var corrupted = false
    private var waitKeyframe = true

    var lostPackets = 0L
        private set

    fun push(pkt: RtpPacket) {
        val ts = unwrapper.unwrap(pkt.timestamp)
        if (lastSeq >= 0) {
            val expected = (lastSeq + 1) and 0xFFFF
            if (pkt.sequence != expected) {
                val gap = (pkt.sequence - expected) and 0xFFFF
                if (gap > 0x8000) return // pacote antigo/duplicado
                lostPackets += gap
                corrupted = true
                waitKeyframe = true // referências perdidas: só um keyframe recupera a imagem
                fuBuffer = null
            }
        }
        lastSeq = pkt.sequence

        if (ts != currentTs) {
            flush()
            currentTs = ts
        }
        if (pkt.payload.isNotEmpty()) parsePayload(pkt.payload)
        if (pkt.marker) flush()
    }

    /** Interpreta o payload RTP específico do codec e chama [addNal] para cada NAL completa. */
    protected abstract fun parsePayload(p: ByteArray)

    /** Classifica a NAL: parameter set, descartável (AUD) ou dado de imagem. */
    protected abstract fun paramSetOf(nal: ByteArray): ParamSet?
    protected abstract fun isDiscardable(nal: ByteArray): Boolean
    protected abstract fun isKeyframe(nal: ByteArray): Boolean

    protected fun addNal(nal: ByteArray) {
        if (nal.isEmpty()) return
        val ps = paramSetOf(nal)
        when {
            ps != null -> onParameterSet(ps, nal)
            isDiscardable(nal) -> Unit
            else -> nalus.add(nal)
        }
    }

    /** Agregação: [i] aponta para o primeiro campo de tamanho (16 bits) de cada NAL. */
    protected fun parseAggregate(p: ByteArray, start: Int) {
        var i = start
        while (i + 2 <= p.size) {
            val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
            i += 2
            if (size == 0 || i + size > p.size) { corrupted = true; break }
            addNal(p.copyOfRange(i, i + size))
            i += size
        }
    }

    /** Fragmentação: começa/continua/termina a NAL reconstruída. */
    protected fun fragment(start: Boolean, end: Boolean, header: ByteArray, p: ByteArray, dataOffset: Int) {
        if (start) fuBuffer = ByteArrayOutputStream(p.size * 8).also { it.write(header) }
        val buf = fuBuffer
        if (buf == null) {
            corrupted = true
            return
        }
        buf.write(p, dataOffset, p.size - dataOffset)
        if (end) {
            addNal(buf.toByteArray())
            fuBuffer = null
        }
    }

    private fun flush() {
        if (fuBuffer != null) { // fragmento incompleto
            corrupted = true
            fuBuffer = null
        }
        if (nalus.isEmpty()) {
            corrupted = false
            return
        }
        val key = nalus.any { isKeyframe(it) }
        if (corrupted) {
            waitKeyframe = true
        } else if (key || !waitKeyframe) {
            waitKeyframe = false
            onAccessUnit(ArrayList(nalus), currentTs, key)
        }
        nalus.clear()
        corrupted = false
    }
}

/** H.264 sobre RTP (RFC 6184): NAL único, STAP-A e FU-A. */
class H264Depacketizer(
    onParameterSet: (kind: ParamSet, nal: ByteArray) -> Unit,
    onAccessUnit: (nalus: List<ByteArray>, rtpTimestamp: Long, keyframe: Boolean) -> Unit,
) : VideoDepacketizer(onParameterSet, onAccessUnit) {

    override fun parsePayload(p: ByteArray) {
        when (val type = p[0].toInt() and 0x1F) {
            in 1..23 -> addNal(p)
            24 -> parseAggregate(p, 1) // STAP-A
            28 -> { // FU-A
                if (p.size < 2) return
                val fu = p[1].toInt() and 0xFF
                val header = byteArrayOf(((p[0].toInt() and 0xE0) or (fu and 0x1F)).toByte())
                fragment(fu and 0x80 != 0, fu and 0x40 != 0, header, p, 2)
            }
            else -> if (type == 0) corrupted = true // STAP-B/MTAP/FU-B: raros, ignorados
        }
    }

    override fun paramSetOf(nal: ByteArray) = when (H264.nalType(nal)) {
        H264.NAL_SPS -> ParamSet.SPS
        H264.NAL_PPS -> ParamSet.PPS
        else -> null
    }

    override fun isDiscardable(nal: ByteArray) = H264.nalType(nal) == H264.NAL_AUD
    override fun isKeyframe(nal: ByteArray) = H264.nalType(nal) == H264.NAL_IDR
}

/**
 * H.265/HEVC sobre RTP (RFC 7798): NAL única, Aggregation Packet (48) e Fragmentation Unit (49).
 * Assume sprop-max-don-diff = 0 (sem campo DONL), que é o padrão das câmeras IP.
 */
class H265Depacketizer(
    onParameterSet: (kind: ParamSet, nal: ByteArray) -> Unit,
    onAccessUnit: (nalus: List<ByteArray>, rtpTimestamp: Long, keyframe: Boolean) -> Unit,
) : VideoDepacketizer(onParameterSet, onAccessUnit) {

    override fun parsePayload(p: ByteArray) {
        if (p.size < 2) return
        when (H265.nalType(p)) {
            H265.NAL_AP -> parseAggregate(p, 2)
            H265.NAL_FU -> {
                if (p.size < 3) return
                val fu = p[2].toInt() and 0xFF
                val type = fu and 0x3F
                val header = byteArrayOf(((p[0].toInt() and 0x81) or (type shl 1)).toByte(), p[1])
                fragment(fu and 0x80 != 0, fu and 0x40 != 0, header, p, 3)
            }
            H265.NAL_PACI -> Unit
            else -> addNal(p)
        }
    }

    override fun paramSetOf(nal: ByteArray) = when (H265.nalType(nal)) {
        H265.NAL_VPS -> ParamSet.VPS
        H265.NAL_SPS -> ParamSet.SPS
        H265.NAL_PPS -> ParamSet.PPS
        else -> null
    }

    override fun isDiscardable(nal: ByteArray) = H265.nalType(nal) == H265.NAL_AUD
    override fun isKeyframe(nal: ByteArray) = H265.nalType(nal) in 16..23 // IRAP: BLA, IDR, CRA
}
