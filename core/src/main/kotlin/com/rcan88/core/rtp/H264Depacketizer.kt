package com.rcan88.core.rtp

import com.rcan88.core.media.H264
import java.io.ByteArrayOutputStream

/**
 * Remonta access units H.264 a partir de pacotes RTP (RFC 6184):
 * NAL único, STAP-A e FU-A.
 *
 * Em caso de perda de pacote o access unit corrompido é descartado e o fluxo
 * só é retomado no próximo keyframe (IDR) — evita artefatos na live.
 */
class H264Depacketizer(
    private val onParameterSet: (type: Int, nal: ByteArray) -> Unit,
    private val onAccessUnit: (nalus: List<ByteArray>, rtpTimestamp: Long, keyframe: Boolean) -> Unit,
) {
    private val unwrapper = TimestampUnwrapper()
    private val nalus = ArrayList<ByteArray>()
    private var currentTs = Long.MIN_VALUE
    private var lastSeq = -1
    private var fuBuffer: ByteArrayOutputStream? = null
    private var corrupted = false
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
                waitKeyframe = true // referências perdidas: só um IDR recupera a imagem
                fuBuffer = null
            }
        }
        lastSeq = pkt.sequence

        if (ts != currentTs) {
            flush()
            currentTs = ts
        }

        val p = pkt.payload
        if (p.isEmpty()) return
        when (val type = p[0].toInt() and 0x1F) {
            in 1..23 -> addNal(p)
            24 -> { // STAP-A
                var i = 1
                while (i + 2 <= p.size) {
                    val size = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)
                    i += 2
                    if (size == 0 || i + size > p.size) { corrupted = true; break }
                    addNal(p.copyOfRange(i, i + size))
                    i += size
                }
            }
            28 -> { // FU-A
                if (p.size < 2) return
                val fuHeader = p[1].toInt() and 0xFF
                val start = fuHeader and 0x80 != 0
                val end = fuHeader and 0x40 != 0
                if (start) {
                    fuBuffer = ByteArrayOutputStream(p.size * 8).also {
                        it.write((p[0].toInt() and 0xE0) or (fuHeader and 0x1F))
                    }
                }
                val buf = fuBuffer
                if (buf == null) {
                    corrupted = true
                } else {
                    buf.write(p, 2, p.size - 2)
                    if (end) {
                        addNal(buf.toByteArray())
                        fuBuffer = null
                    }
                }
            }
            else -> { /* STAP-B, MTAP, FU-B: raros em câmeras IP — ignorados */ if (type == 0) corrupted = true }
        }

        if (pkt.marker) flush()
    }

    private fun addNal(nal: ByteArray) {
        when (H264.nalType(nal)) {
            H264.NAL_SPS, H264.NAL_PPS -> onParameterSet(H264.nalType(nal), nal)
            H264.NAL_AUD -> Unit
            else -> nalus.add(nal)
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
        val key = nalus.any { H264.nalType(it) == H264.NAL_IDR }
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
