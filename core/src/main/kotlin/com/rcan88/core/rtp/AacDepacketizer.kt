package com.rcan88.core.rtp

import java.io.ByteArrayOutputStream

/**
 * Extrai frames AAC de pacotes RTP "mpeg4-generic" modo AAC-hbr/AAC-lbr (RFC 3640).
 * Cada frame AAC tem 1024 amostras; o timestamp de cada AU é derivado do pacote.
 */
class AacDepacketizer(
    private val sizeLength: Int = 13,
    private val indexLength: Int = 3,
    private val indexDeltaLength: Int = 3,
    private val onFrame: (data: ByteArray, rtpTimestamp: Long) -> Unit,
) {
    private val unwrapper = TimestampUnwrapper()
    private var fragment: ByteArrayOutputStream? = null
    private var fragmentSize = 0
    private var fragmentTs = 0L

    fun push(pkt: RtpPacket) {
        val ts = unwrapper.unwrap(pkt.timestamp)
        val p = pkt.payload
        if (p.size < 2) return
        val headersBits = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
        val headersBytes = (headersBits + 7) / 8
        var dataPos = 2 + headersBytes
        if (dataPos > p.size) return

        // Lê os AU-headers
        val sizes = ArrayList<Int>()
        var bit = 16
        val headerEnd = 16 + headersBits
        var first = true
        while (bit + sizeLength <= headerEnd) {
            sizes.add(readBits(p, bit, sizeLength))
            bit += sizeLength + if (first) indexLength else indexDeltaLength
            first = false
        }

        // Continuação de AU fragmentado
        fragment?.let { frag ->
            if (sizes.size == 1 && ts == fragmentTs) {
                frag.write(p, dataPos, p.size - dataPos)
                if (frag.size() >= fragmentSize) {
                    onFrame(frag.toByteArray().copyOf(fragmentSize), fragmentTs)
                    fragment = null
                }
                return
            }
            fragment = null // fragmento perdido
        }

        sizes.forEachIndexed { i, size ->
            val available = p.size - dataPos
            if (available <= 0) return
            if (size > available) {
                if (sizes.size == 1) { // AU maior que o pacote: começa fragmentação
                    fragment = ByteArrayOutputStream(size).also { it.write(p, dataPos, available) }
                    fragmentSize = size
                    fragmentTs = ts
                }
                return
            }
            onFrame(p.copyOfRange(dataPos, dataPos + size), ts + i * 1024L)
            dataPos += size
        }
    }

    private fun readBits(data: ByteArray, startBit: Int, count: Int): Int {
        var v = 0
        for (i in 0 until count) {
            val b = startBit + i
            v = (v shl 1) or ((data[b shr 3].toInt() shr (7 - (b and 7))) and 1)
        }
        return v
    }
}
