package com.rcan88.core.rtsp

import com.rcan88.core.media.AudioConfig
import com.rcan88.core.media.AudioFrame
import com.rcan88.core.media.VideoCodec
import com.rcan88.core.media.VideoConfig
import com.rcan88.core.media.VideoFrame
import com.rcan88.core.rtp.AacDepacketizer
import com.rcan88.core.rtp.H264Depacketizer
import com.rcan88.core.rtp.H265Depacketizer
import com.rcan88.core.rtp.ParamSet
import com.rcan88.core.rtp.RtpPacket
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.Base64
import javax.net.SocketFactory

class RtspException(message: String) : IOException(message)

interface RtspListener {
    fun onVideoConfig(config: VideoConfig)
    fun onAudioConfig(config: AudioConfig) {}
    fun onVideoFrame(frame: VideoFrame)
    fun onAudioFrame(frame: AudioFrame) {}
    fun onLog(message: String) {}
}

/**
 * Cliente RTSP mínimo que "puxa" o fluxo de uma câmera IP usando RTP intercalado no TCP
 * (RTP/AVP/TCP) — mais robusto que UDP em redes Wi-Fi e atravessa NAT sem configuração.
 *
 * Suporta: H.264 e H.265/HEVC (vídeo), AAC mpeg4-generic (áudio), autenticação Basic/Digest.
 */
class RtspClient(
    url: String,
    user: String? = null,
    password: String? = null,
    private val enableAudio: Boolean = true,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val timeoutMs: Int = 10_000,
    private val listener: RtspListener,
) {
    private val target = RtspUrl.parse(url)
    private val auth: RtspAuth? = (user?.takeIf { it.isNotEmpty() } ?: target.user)?.let {
        RtspAuth(it, password?.takeIf { p -> p.isNotEmpty() } ?: target.password ?: "")
    }
    private val baseUrl = target.cleanUrl

    @Volatile private var socket: Socket? = null
    @Volatile private var stopped = false
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private var cseq = 0
    private var session: String? = null
    private var sessionTimeoutSec = 60

    private val tracks = HashMap<Int, Track>() // canal RTP -> trilha
    private var playStartNs = 0L

    private var codec = VideoCodec.H264
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var currentVideoConfig: VideoConfig? = null

    /** Conecta, negocia e lê o fluxo até [stop] ou erro (lança exceção). */
    fun run() {
        try {
            val s = socketFactory.createSocket()
            socket = s
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
            s.receiveBufferSize = 512 * 1024
            s.connect(InetSocketAddress(target.host, target.port), timeoutMs)
            input = BufferedInputStream(s.getInputStream(), 64 * 1024)
            output = s.getOutputStream()

            request("OPTIONS", baseUrl)
            val describe = request("DESCRIBE", baseUrl, listOf("Accept" to "application/sdp"))
            val contentBase = describe.header("content-base") ?: describe.header("content-location") ?: baseUrl
            val sdp = SdpParser.parse(String(describe.body, Charsets.UTF_8))
            listener.onLog("SDP: " + sdp.media.joinToString { "${it.type}/${it.encoding}" })
            val sessionBase = SdpParser.resolveControl(contentBase, sdp.control)

            val video = sdp.media.firstOrNull { it.type == "video" }
                ?: throw RtspException("A câmera não oferece trilha de vídeo")
            if (video.encoding != "H264" && video.encoding != "H265") {
                throw RtspException(
                    "Codec de vídeo '${video.encoding}' não suportado. Configure a câmera para H.264 ou H.265."
                )
            }
            setupVideo(video, sessionBase)

            if (enableAudio) {
                val audio = sdp.media.firstOrNull { it.type == "audio" }
                if (audio != null) {
                    val mode = audio.fmtp["mode"]?.lowercase()
                    if (audio.encoding == "MPEG4-GENERIC" && (mode == null || mode.startsWith("aac")) && audio.fmtp["config"] != null) {
                        setupAudio(audio, sessionBase)
                    } else {
                        listener.onLog("Áudio ${audio.encoding} não suportado (o YouTube exige AAC). Seguindo só com vídeo.")
                    }
                }
            }

            request("PLAY", sessionBase, listOf("Range" to "npt=0.000-"))
            playStartNs = System.nanoTime()
            listener.onLog("Câmera conectada: ${target.host}:${target.port}")
            readLoop(sessionBase)
        } finally {
            close()
        }
    }

    fun stop() {
        stopped = true
        close()
    }

    private fun close() {
        try { socket?.close() } catch (_: IOException) {}
    }

    // ---------------------------------------------------------------- setup

    private fun setupVideo(m: SdpMedia, base: String) {
        codec = if (m.encoding == "H265") VideoCodec.H265 else VideoCodec.H264
        fun decode(b64: String) = runCatching { Base64.getDecoder().decode(b64.trim()) }.getOrNull()
        if (codec == VideoCodec.H264) {
            m.fmtp["sprop-parameter-sets"]?.split(',')?.forEach { b64 ->
                val nal = decode(b64) ?: return@forEach
                when (nal.firstOrNull()?.toInt()?.and(0x1F)) {
                    7 -> onParameterSet(ParamSet.SPS, nal)
                    8 -> onParameterSet(ParamSet.PPS, nal)
                }
            }
        } else {
            m.fmtp["sprop-vps"]?.let { decode(it) }?.let { onParameterSet(ParamSet.VPS, it) }
            m.fmtp["sprop-sps"]?.let { decode(it) }?.let { onParameterSet(ParamSet.SPS, it) }
            m.fmtp["sprop-pps"]?.let { decode(it) }?.let { onParameterSet(ParamSet.PPS, it) }
        }
        val channel = tracks.size * 2
        val track = Track(m.clockRate.takeIf { it > 0 } ?: 90000)
        val onFrame = { nalus: List<ByteArray>, ts: Long, key: Boolean ->
            listener.onVideoFrame(VideoFrame(nalus, track.ptsMs(ts), key))
        }
        val depack = if (codec == VideoCodec.H264) H264Depacketizer(::onParameterSet, onFrame)
        else H265Depacketizer(::onParameterSet, onFrame)
        track.push = depack::push
        track.lostPackets = { depack.lostPackets }
        setup(SdpParser.resolveControl(base, m.control), channel)
        tracks[channel] = track
    }

    private fun setupAudio(m: SdpMedia, base: String) {
        val asc = hexToBytes(m.fmtp.getValue("config"))
        val config = AudioConfig(asc)
        val track = Track(m.clockRate.takeIf { it > 0 } ?: config.sampleRate)
        val depack = AacDepacketizer(
            sizeLength = m.fmtp["sizelength"]?.toIntOrNull() ?: 13,
            indexLength = m.fmtp["indexlength"]?.toIntOrNull() ?: 3,
            indexDeltaLength = m.fmtp["indexdeltalength"]?.toIntOrNull() ?: 3,
        ) { data, ts -> listener.onAudioFrame(AudioFrame(data, track.ptsMs(ts))) }
        track.push = depack::push
        val channel = tracks.size * 2
        setup(SdpParser.resolveControl(base, m.control), channel)
        tracks[channel] = track
        listener.onAudioConfig(config)
    }

    private fun setup(controlUrl: String, channel: Int) {
        val resp = request(
            "SETUP", controlUrl,
            listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}")
        )
        resp.header("session")?.let { s ->
            val parts = s.split(';')
            session = parts[0].trim()
            parts.drop(1).map { it.trim() }.firstOrNull { it.startsWith("timeout=", true) }
                ?.substringAfter('=')?.toIntOrNull()?.let { sessionTimeoutSec = it.coerceAtLeast(5) }
        }
        val transport = resp.header("transport") ?: ""
        if (!transport.contains("TCP", ignoreCase = true) && !transport.contains("interleaved", ignoreCase = true)) {
            throw RtspException("A câmera recusou RTP sobre TCP (Transport: $transport)")
        }
    }

    private fun onParameterSet(kind: ParamSet, nal: ByteArray) {
        when (kind) {
            ParamSet.VPS -> vps = nal
            ParamSet.SPS -> sps = nal
            ParamSet.PPS -> pps = nal
        }
        val s = sps ?: return
        val p = pps ?: return
        if (codec == VideoCodec.H265 && vps == null) return
        val cfg = VideoConfig(s, p, codec, if (codec == VideoCodec.H265) vps else null)
        if (!cfg.sameAs(currentVideoConfig)) {
            currentVideoConfig = cfg
            listener.onVideoConfig(cfg)
        }
    }

    // ---------------------------------------------------------------- loop

    private fun readLoop(keepAliveUrl: String) {
        var lastKeepAlive = System.nanoTime()
        val keepAliveNs = sessionTimeoutSec * 1_000_000_000L / 2
        val buf = ByteArray(65_536)
        while (!stopped) {
            val first = input.read()
            if (first < 0) throw EOFException("A câmera encerrou a conexão")
            if (first == '$'.code) {
                val channel = readByte()
                val len = (readByte() shl 8) or readByte()
                readFully(buf, len)
                val track = tracks[channel]
                if (track != null) {
                    RtpPacket.parse(buf, 0, len)?.let { pkt -> track.push(pkt) }
                }
                // canais ímpares = RTCP (ignorado nesta versão)
            } else {
                readMessage(first) // resposta a keep-alive ou requisição do servidor
            }
            val now = System.nanoTime()
            if (now - lastKeepAlive > keepAliveNs) {
                lastKeepAlive = now
                send("GET_PARAMETER", keepAliveUrl, emptyList())
            }
        }
    }

    val lostPackets: Long get() = tracks.values.sumOf { it.lostPackets() }

    private inner class Track(val clockRate: Int) {
        var push: (RtpPacket) -> Unit = {}
        var lostPackets: () -> Long = { 0L }
        private var firstTs = Long.MIN_VALUE
        private var offsetMs = 0L

        /** Converte timestamp RTP estendido em ms, alinhando trilhas pelo instante de chegada. */
        fun ptsMs(ts: Long): Long {
            if (firstTs == Long.MIN_VALUE) {
                firstTs = ts
                offsetMs = (System.nanoTime() - playStartNs) / 1_000_000
            }
            return offsetMs + (ts - firstTs) * 1000 / clockRate
        }
    }

    // ---------------------------------------------------------------- protocolo

    private class Response(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(name: String) = headers.firstOrNull { it.first == name }?.second
        fun headers(name: String) = headers.filter { it.first == name }.map { it.second }
    }

    private fun request(method: String, uri: String, extra: List<Pair<String, String>> = emptyList()): Response {
        var resp = exchange(method, uri, extra)
        if (resp.status == 401 && auth != null) {
            if (!auth.onChallenge(resp.headers("www-authenticate"))) {
                throw RtspException("Método de autenticação da câmera não suportado")
            }
            resp = exchange(method, uri, extra)
        }
        when {
            resp.status == 401 -> throw RtspException("Usuário ou senha da câmera inválidos (401)")
            resp.status == 404 -> throw RtspException("Caminho RTSP não encontrado (404): confira a URL da câmera")
            resp.status != 200 -> throw RtspException("$method falhou: RTSP ${resp.status}")
        }
        return resp
    }

    private fun exchange(method: String, uri: String, extra: List<Pair<String, String>>): Response {
        val seq = send(method, uri, extra)
        while (true) {
            val first = input.read()
            if (first < 0) throw EOFException("Conexão RTSP fechada durante $method")
            if (first == '$'.code) { // dado intercalado antes da resposta: descarta
                readByte()
                val len = (readByte() shl 8) or readByte()
                input.skipNBytesCompat(len)
                continue
            }
            val resp = readMessage(first) ?: continue
            if (resp.header("cseq")?.trim()?.toIntOrNull() == seq) return resp
        }
    }

    private fun send(method: String, uri: String, extra: List<Pair<String, String>>): Int {
        val seq = ++cseq
        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: $seq\r\n")
        sb.append("User-Agent: 88Rcan/0.1\r\n")
        session?.let { sb.append("Session: $it\r\n") }
        auth?.header(method, uri)?.let { sb.append("Authorization: $it\r\n") }
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        synchronized(this) {
            output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
        return seq
    }

    /** Lê uma mensagem RTSP cujo primeiro byte já foi consumido. Retorna null se for uma requisição. */
    private fun readMessage(firstByte: Int): Response? {
        val startLine = firstByte.toChar() + readLine()
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers.add(line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim())
        }
        val len = headers.firstOrNull { it.first == "content-length" }?.second?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        readFully(body, len)
        if (!startLine.startsWith("RTSP/")) return null
        val status = startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
        return Response(status, headers, body)
    }

    private fun readLine(): String {
        val out = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException()
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > 8192) throw RtspException("Linha RTSP muito longa")
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun readFully(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun InputStream.skipNBytesCompat(n: Int) {
        var left = n.toLong()
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) throw EOFException(); left-- } else left -= s
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** URL RTSP decomposta; credenciais embutidas (rtsp://user:senha@host/...) são extraídas. */
data class RtspUrl(val host: String, val port: Int, val user: String?, val password: String?, val cleanUrl: String) {
    companion object {
        fun parse(url: String): RtspUrl {
            val schemeEnd = url.indexOf("://")
            require(schemeEnd > 0 && url.substring(0, schemeEnd).equals("rtsp", true)) {
                "A URL da câmera deve começar com rtsp://"
            }
            val rest = url.substring(schemeEnd + 3)
            val pathStart = rest.indexOf('/').let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, pathStart)
            val path = rest.substring(pathStart)
            val at = authority.lastIndexOf('@')
            var user: String? = null
            var pass: String? = null
            val hostPort = if (at >= 0) {
                val cred = authority.substring(0, at)
                val c = cred.indexOf(':')
                user = decode(if (c >= 0) cred.substring(0, c) else cred)
                pass = if (c >= 0) decode(cred.substring(c + 1)) else null
                authority.substring(at + 1)
            } else authority
            val colon = hostPort.lastIndexOf(':')
            val (host, port) = if (colon > 0 && !hostPort.endsWith("]")) {
                hostPort.substring(0, colon) to (hostPort.substring(colon + 1).toIntOrNull() ?: 554)
            } else hostPort to 554
            require(host.isNotEmpty()) { "Host da câmera ausente na URL" }
            return RtspUrl(host, port, user, pass, "rtsp://$hostPort$path")
        }

        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    }
}
