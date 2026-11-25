package com.rcan88.core

import com.rcan88.core.media.AudioConfig
import com.rcan88.core.media.HevcSpsParser
import com.rcan88.core.media.VideoCodec
import com.rcan88.core.media.VideoConfig
import com.rcan88.core.rtmp.FlvTags
import com.rcan88.core.rtp.H265Depacketizer
import com.rcan88.core.rtp.ParamSet
import com.rcan88.core.media.SpsParser
import com.rcan88.core.rtmp.Amf0
import com.rcan88.core.rtmp.RtmpUrl
import com.rcan88.core.rtp.AacDepacketizer
import com.rcan88.core.rtp.H264Depacketizer
import com.rcan88.core.rtp.RtpPacket
import com.rcan88.core.rtp.TimestampUnwrapper
import com.rcan88.core.rtsp.RtspAuth
import com.rcan88.core.rtsp.RtspUrl
import com.rcan88.core.rtsp.SdpParser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun rtp(seq: Int, ts: Long, marker: Boolean, payload: ByteArray, pt: Int = 96): RtpPacket {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = ((if (marker) 0x80 else 0) or pt).toByte()
        h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
        h[4] = (ts shr 24).toByte(); h[5] = (ts shr 16).toByte(); h[6] = (ts shr 8).toByte(); h[7] = ts.toByte()
        return RtpPacket.parse(h + payload)!!
    }

    @Test fun `SPS 720p baseline`() {
        val info = SpsParser.parse(hex("6742c01fd9005005bb0110000003001000000303c0f1832480"))
        assertEquals(1280, info.width); assertEquals(720, info.height); assertEquals(66, info.profileIdc)
    }

    @Test fun `SPS 1080p high com cropping`() {
        val info = SpsParser.parse(hex("67640028acd940780227e5c044000003000400000300f03c60c658"))
        assertEquals(1920, info.width); assertEquals(1080, info.height); assertEquals(100, info.profileIdc)
    }

    @Test fun `SDP tipico de camera IP`() {
        val sdp = SdpParser.parse(
            """
            v=0
            o=- 1 1 IN IP4 192.168.1.10
            s=Session
            a=control:*
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=4D001F;sprop-parameter-sets=Z00AH5WoFAFuQA==,aO48gA==
            a=control:trackID=1
            m=audio 0 RTP/AVP 97
            a=rtpmap:97 MPEG4-GENERIC/16000/1
            a=fmtp:97 streamtype=5;profile-level-id=15;mode=AAC-hbr;config=1408;SizeLength=13;IndexLength=3;IndexDeltaLength=3
            a=control:trackID=2
            """.trimIndent()
        )
        assertEquals(2, sdp.media.size)
        val v = sdp.media[0]
        assertEquals("H264", v.encoding); assertEquals(90000, v.clockRate); assertEquals("trackID=1", v.control)
        assertEquals("Z00AH5WoFAFuQA==,aO48gA==", v.fmtp["sprop-parameter-sets"])
        val a = sdp.media[1]
        assertEquals("MPEG4-GENERIC", a.encoding); assertEquals(16000, a.clockRate); assertEquals("13", a.fmtp["sizelength"])
        assertEquals("rtsp://cam/s/trackID=1", SdpParser.resolveControl("rtsp://cam/s/", "trackID=1"))
        assertEquals("rtsp://cam/s/trackID=1", SdpParser.resolveControl("rtsp://cam/s", "trackID=1"))
        assertEquals("rtsp://x/y", SdpParser.resolveControl("rtsp://cam/s", "rtsp://x/y"))
        val asc = AudioConfig(hex("1408"))
        assertEquals(16000, asc.sampleRate); assertEquals(1, asc.channels)
    }

    @Test fun `URL RTSP com credenciais`() {
        val u = RtspUrl.parse("rtsp://admin:p%40ss:1@192.168.0.5:8554/Streaming/Channels/101")
        assertEquals("192.168.0.5", u.host); assertEquals(8554, u.port)
        assertEquals("admin", u.user); assertEquals("p@ss:1", u.password)
        assertEquals("rtsp://192.168.0.5:8554/Streaming/Channels/101", u.cleanUrl)
        assertEquals(554, RtspUrl.parse("rtsp://cam/live").port)
    }

    @Test fun `URL RTMP do YouTube`() {
        val u = RtmpUrl.parse("rtmps://a.rtmps.youtube.com/live2/abcd-efgh-ijkl")
        assertTrue(u.secure); assertEquals(443, u.port); assertEquals("live2", u.app); assertEquals("abcd-efgh-ijkl", u.stream)
        assertEquals("rtmps://a.rtmps.youtube.com/live2", u.tcUrl)
        val n = RtmpUrl.parse("rtmp://127.0.0.1:1936/live/test")
        assertFalse(n.secure); assertEquals(1936, n.port)
    }

    @Test fun `Digest RFC 2617`() {
        // exemplo da RFC 2617 sem qop (HA1/HA2 clássicos)
        val auth = RtspAuth("Mufasa", "Circle Of Life")
        auth.onChallenge(listOf("Digest realm=\"testrealm@host.com\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""))
        val h = auth.header("GET", "/dir/index.html")!!
        assertTrue(h.contains("response=\"670fd8c2df070c60b045671b8b24ff02\""), h)
    }

    @Test fun `AMF0 ida e volta`() {
        val data = Amf0.encode("_result", 1.0, mapOf("code" to "NetStream.Publish.Start", "ok" to true), null)
        val back = Amf0.decodeAll(data)
        assertEquals("_result", back[0]); assertEquals(1.0, back[1])
        assertEquals("NetStream.Publish.Start", (back[2] as Map<*, *>)["code"]); assertEquals(null, back[3])
    }

    @Test fun `H264 FU-A STAP-A e perda de pacote`() {
        val sps = byteArrayOf(0x67, 1, 2, 3); val pps = byteArrayOf(0x68, 4)
        val idr = ByteArray(3000) { if (it == 0) 0x65 else (it % 251).toByte() }
        val params = ArrayList<ParamSet>()
        val frames = ArrayList<Pair<List<ByteArray>, Boolean>>()
        val d = H264Depacketizer({ t, _ -> params.add(t) }) { n, _, k -> frames.add(n to k) }

        // STAP-A com SPS+PPS
        d.push(rtp(1, 1000, false, byteArrayOf(24, 0, 4) + sps + byteArrayOf(0, 2) + pps))
        // IDR fragmentado em FU-A
        val body = idr.copyOfRange(1, idr.size)
        val chunks = body.toList().chunked(1200).map { it.toByteArray() }
        chunks.forEachIndexed { i, c ->
            val s = if (i == 0) 0x80 else 0; val e = if (i == chunks.lastIndex) 0x40 else 0
            d.push(rtp(2 + i, 1000, i == chunks.lastIndex, byteArrayOf(0x7C, (s or e or 5).toByte()) + c))
        }
        assertEquals(listOf(ParamSet.SPS, ParamSet.PPS), params)
        assertEquals(1, frames.size); assertTrue(frames[0].second)
        assertContentEquals(idr, frames[0].first[0])

        // P-frame normal
        d.push(rtp(5, 4000, true, byteArrayOf(0x41, 9, 9)))
        assertEquals(2, frames.size)
        // perda (seq 6 sumiu) -> P-frame descartado, espera próximo IDR
        d.push(rtp(7, 7000, true, byteArrayOf(0x41, 8)))
        d.push(rtp(8, 10000, true, byteArrayOf(0x41, 7)))
        assertEquals(2, frames.size)
        d.push(rtp(9, 13000, true, byteArrayOf(0x65, 1)))
        assertEquals(3, frames.size); assertTrue(frames[2].second)
        assertEquals(1, d.lostPackets)
    }

    @Test fun `AAC RFC3640 com dois AUs`() {
        val got = ArrayList<Pair<Int, Long>>()
        val d = AacDepacketizer { data, ts -> got.add(data.size to ts) }
        // 2 AU-headers (16 bits cada): tamanho 5 e 7, index 0
        val headers = byteArrayOf(0, 32, (5 shr 5).toByte(), ((5 shl 3) and 0xFF).toByte(), (7 shr 5).toByte(), ((7 shl 3) and 0xFF).toByte())
        d.push(rtp(1, 5000, true, headers + ByteArray(5) + ByteArray(7), 97))
        assertEquals(listOf(5 to 5000L, 7 to 6024L), got)
    }

    private val hevcVps = hex("40010c01ffff016000000300900000030000030096959809")
    private val hevcSps = hex("420101016000000300900000030000030096a003c080087165959a4932bc05a020000003002000000301e1")

    @Test fun `SPS HEVC 1920x2160 (camera dupla lente)`() {
        val info = HevcSpsParser.parse(hevcSps)
        assertEquals(1920, info.width); assertEquals(2160, info.height)
        assertEquals(1, info.profileIdc) // Main
        assertEquals(1, info.chromaFormatIdc); assertEquals(0, info.bitDepthLumaMinus8)
    }

    @Test fun `hvcC no Enhanced RTMP`() {
        val cfg = VideoConfig(hevcSps, byteArrayOf(0x44, 0x01, 0xC1.toByte()), VideoCodec.H265, hevcVps)
        val tag = FlvTags.videoSequenceHeader(cfg)
        assertEquals(0x90, tag[0].toInt() and 0xFF) // ex-header + keyframe + SequenceStart
        assertEquals("hvc1", String(tag, 1, 4))
        assertEquals(1, tag[5].toInt()) // configurationVersion
        assertEquals(3, tag[27].toInt()) // numOfArrays
        assertEquals(0x80 or 32, tag[28].toInt() and 0xFF) // primeiro array = VPS
        val frame = FlvTags.videoFrame(VideoCodec.H265, listOf(byteArrayOf(0x26, 0x01, 9)), keyframe = false)
        assertEquals(0xA1, frame[0].toInt() and 0xFF) // ex-header + inter + CodedFrames
    }

    @Test fun `H265 AP e FU`() {
        val params = ArrayList<ParamSet>()
        val frames = ArrayList<Pair<List<ByteArray>, Boolean>>()
        val d = H265Depacketizer({ k, _ -> params.add(k) }) { n, _, k -> frames.add(n to k) }
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        // AP (tipo 48) com VPS+SPS+PPS
        val ap = byteArrayOf(0x60, 0x01) + listOf(hevcVps, hevcSps, pps).flatMap {
            listOf((it.size shr 8).toByte(), it.size.toByte()) + it.toList()
        }.toByteArray()
        d.push(rtp(1, 9000, false, ap))
        // IDR_W_RADL (tipo 19) fragmentado em FU (tipo 49)
        val idr = ByteArray(2500) { when (it) { 0 -> 0x26; 1 -> 0x01; else -> (it % 249).toByte() } }
        val chunks = idr.copyOfRange(2, idr.size).toList().chunked(1000).map { it.toByteArray() }
        chunks.forEachIndexed { i, c ->
            val s = if (i == 0) 0x80 else 0; val e = if (i == chunks.lastIndex) 0x40 else 0
            d.push(rtp(2 + i, 9000, i == chunks.lastIndex, byteArrayOf(0x62, 0x01, (s or e or 19).toByte()) + c))
        }
        assertEquals(listOf(ParamSet.VPS, ParamSet.SPS, ParamSet.PPS), params)
        assertEquals(1, frames.size); assertTrue(frames[0].second)
        assertContentEquals(idr, frames[0].first[0])
        d.push(rtp(5, 15000, true, byteArrayOf(0x02, 0x01, 5))) // TRAIL_R
        assertEquals(2, frames.size); assertFalse(frames[1].second)
    }

    @Test fun `timestamp RTP com wraparound`() {
        val u = TimestampUnwrapper()
        assertEquals(0xFFFFFF00L, u.unwrap(0xFFFFFF00L))
        assertEquals(0x100000010L, u.unwrap(0x10L))
    }
}
