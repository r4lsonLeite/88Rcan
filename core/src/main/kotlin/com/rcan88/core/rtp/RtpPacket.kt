package com.rcan88.core.rtp

/** Pacote RTP (RFC 3550). [payload] já sem cabeçalho, CSRCs, extensão e padding. */
class RtpPacket(
    val payloadType: Int,
    val marker: Boolean,
    val sequence: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray,
) {
    companion object {
        fun parse(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): RtpPacket? {
            if (length < 12) return null
            val b0 = buf[offset].toInt() and 0xFF
            if (b0 shr 6 != 2) return null
            val padding = b0 and 0x20 != 0
            val extension = b0 and 0x10 != 0
            val cc = b0 and 0x0F
            val b1 = buf[offset + 1].toInt() and 0xFF
            val seq = u16(buf, offset + 2)
            val ts = u32(buf, offset + 4)
            val ssrc = u32(buf, offset + 8)
            var start = offset + 12 + cc * 4
            var end = offset + length
            if (extension) {
                if (start + 4 > end) return null
                val extLen = u16(buf, start + 2)
                start += 4 + extLen * 4
            }
            if (padding && end > start) {
                end -= buf[end - 1].toInt() and 0xFF
            }
            if (start > end) return null
            return RtpPacket(b1 and 0x7F, b1 and 0x80 != 0, seq, ts, ssrc, buf.copyOfRange(start, end))
        }

        private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        private fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
                ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
    }
}

/** Converte timestamps RTP de 32 bits em contador estendido de 64 bits (trata o wraparound). */
class TimestampUnwrapper {
    private var last = -1L
    private var cycles = 0L

    fun unwrap(ts: Long): Long {
        if (last >= 0) {
            val diff = ts - last
            if (diff < -0x80000000L) cycles += 0x100000000L
            else if (diff > 0x80000000L) cycles -= 0x100000000L
        }
        last = ts
        return ts + cycles
    }
}
