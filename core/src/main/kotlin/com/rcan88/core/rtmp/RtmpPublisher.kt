package com.rcan88.core.rtmp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class RtmpException(message: String) : IOException(message)

/** Destino RTMP: rtmp(s)://host[:porta]/app/.../chaveDaTransmissao */
data class RtmpUrl(val secure: Boolean, val host: String, val port: Int, val app: String, val stream: String, val tcUrl: String) {
    companion object {
        fun parse(url: String): RtmpUrl {
            val schemeEnd = url.indexOf("://")
            require(schemeEnd > 0) { "URL RTMP inválida" }
            val scheme = url.substring(0, schemeEnd).lowercase()
            require(scheme == "rtmp" || scheme == "rtmps") { "A URL de destino deve começar com rtmp:// ou rtmps://" }
            val secure = scheme == "rtmps"
            val rest = url.substring(schemeEnd + 3)
            val slash = rest.indexOf('/')
            require(slash > 0) { "URL RTMP sem aplicação/chave" }
            val hostPort = rest.substring(0, slash)
            val path = rest.substring(slash + 1).trimEnd('/')
            val lastSlash = path.lastIndexOf('/')
            require(lastSlash > 0) { "A URL RTMP precisa terminar com /<aplicação>/<chave>" }
            val app = path.substring(0, lastSlash)
            val stream = path.substring(lastSlash + 1)
            val colon = hostPort.lastIndexOf(':')
            val host = if (colon > 0) hostPort.substring(0, colon) else hostPort
            val port = if (colon > 0) hostPort.substring(colon + 1).toInt() else if (secure) 443 else 1935
            return RtmpUrl(secure, host, port, app, stream, "$scheme://$hostPort/$app")
        }
    }
}

/**
 * Publicador RTMP/RTMPS mínimo (handshake simples, chunking, comandos AMF0).
 * Compatível com YouTube Live, Facebook, nginx-rtmp, MediaMTX, OBS-like servers.
 *
 * Uso: [connect] → [sendMetadata] → [sendVideo]/[sendAudio] → [close].
 * Os envios são bloqueantes; chame sempre da mesma thread.
 */
class RtmpPublisher(
    url: String,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val timeoutMs: Int = 10_000,
    private val onLog: (String) -> Unit = {},
    /** FourCCs do Enhanced RTMP anunciados no connect (ex.: "hvc1" para HEVC). */
    private val fourCcList: List<String> = emptyList(),
) {
    private val target = RtmpUrl.parse(url)
    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private val writeLock = Any()

    private var outChunkSize = 128
    private var inChunkSize = 128
    private var windowAckSize = 2_500_000L
    private var bytesReceived = 0L
    private var lastAckSent = 0L
    private var streamId = 0
    private var txn = 0

    @Volatile private var failure: String? = null
    @Volatile private var closed = false

    @Volatile var bytesSent = 0L
        private set

    private val inStates = HashMap<Int, ChunkState>()

    fun connect() {
        val raw = socketFactory.createSocket()
        raw.tcpNoDelay = true
        raw.soTimeout = timeoutMs
        raw.sendBufferSize = 256 * 1024
        raw.connect(InetSocketAddress(target.host, target.port), timeoutMs)
        val s = if (target.secure) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, target.host, target.port, true)
                .also { (it as SSLSocket).startHandshake() }
        } else raw
        socket = s
        input = BufferedInputStream(s.getInputStream(), 16 * 1024)
        output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)

        handshake()
        setChunkSize(4096)

        val connectObj = linkedMapOf<String, Any?>(
            "app" to target.app,
            "type" to "nonprivate",
            "flashVer" to "FMLE/3.0 (compatible; 88Rcan)",
            "tcUrl" to target.tcUrl,
        )
        if (fourCcList.isNotEmpty()) connectObj["fourCcList"] = fourCcList
        command("connect", ++txn, connectObj)
        expectResult("connect")

        command("releaseStream", ++txn, null, target.stream)
        command("FCPublish", ++txn, null, target.stream)
        val createTxn = ++txn
        command("createStream", createTxn, null)
        streamId = waitFor { name, values ->
            if (name == "_result" && (values.getOrNull(1) as? Double)?.toInt() == createTxn) {
                (values.getOrNull(3) as? Double)?.toInt() ?: 1
            } else null
        }

        command("publish", ++txn, null, target.stream, "live", streamId = streamId, csid = 4)
        waitFor { name, values ->
            if (name == "onStatus") {
                val info = values.getOrNull(3) as? Map<*, *>
                val code = info?.get("code") as? String
                when {
                    code == "NetStream.Publish.Start" -> true
                    code != null && (code.contains("BadName") || code.contains("Failed") || code.contains("Rejected")) ->
                        throw RtmpException("Servidor recusou a publicação: $code (confira a chave da transmissão)")
                    else -> null
                }
            } else null
        }
        onLog("Publicando em ${target.host} (${if (target.secure) "RTMPS" else "RTMP"})")

        // A partir daqui uma thread lê o canal de retorno (ping, ack, erros) sem bloquear os envios.
        s.soTimeout = 0
        Thread(::readerLoop, "rtmp-reader").apply { isDaemon = true }.start()
    }

    fun sendMetadata(amf: ByteArray) = sendMessage(csid = 5, type = 18, ts = 0, streamId = streamId, payload = amf)

    fun sendVideo(flvBody: ByteArray, tsMs: Long) = sendMessage(csid = 6, type = 9, ts = tsMs, streamId = streamId, payload = flvBody)

    fun sendAudio(flvBody: ByteArray, tsMs: Long) = sendMessage(csid = 4, type = 8, ts = tsMs, streamId = streamId, payload = flvBody)

    fun close() {
        if (closed) return
        closed = true
        runCatching {
            if (streamId != 0) {
                command("FCUnpublish", ++txn, null, target.stream)
                command("deleteStream", ++txn, null, streamId.toDouble())
            }
        }
        runCatching { socket?.close() }
    }

    // ------------------------------------------------------------------ handshake

    private fun handshake() {
        val c1 = ByteArray(1536)
        SecureRandom().nextBytes(c1)
        for (i in 0 until 8) c1[i] = 0 // time + zero
        output.write(3)
        output.write(c1)
        output.flush()
        val s0 = input.read()
        if (s0 != 3) throw RtmpException("Handshake RTMP inválido (versão $s0)")
        val s1 = ByteArray(1536); readFully(s1)
        val s2 = ByteArray(1536); readFully(s2)
        output.write(s1) // C2 = eco do S1
        output.flush()
    }

    // ------------------------------------------------------------------ envio

    private fun setChunkSize(size: Int) {
        val p = byteArrayOf((size ushr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
        sendMessage(csid = 2, type = 1, ts = 0, streamId = 0, payload = p)
        outChunkSize = size
    }

    private fun command(name: String, txnId: Int, vararg args: Any?, streamId: Int = 0, csid: Int = 3) {
        val payload = Amf0.encode(name, txnId.toDouble(), *args)
        sendMessage(csid = csid, type = 20, ts = 0, streamId = streamId, payload = payload)
    }

    private fun sendMessage(csid: Int, type: Int, ts: Long, streamId: Int, payload: ByteArray) {
        failure?.let { throw RtmpException(it) }
        val extended = ts >= 0xFFFFFF
        val ts24 = if (extended) 0xFFFFFF else ts.toInt()
        synchronized(writeLock) {
            val o = output
            // fmt 0: cabeçalho completo
            o.write(csid and 0x3F)
            o.write(ts24 shr 16); o.write(ts24 shr 8); o.write(ts24)
            o.write(payload.size shr 16); o.write(payload.size shr 8); o.write(payload.size)
            o.write(type)
            o.write(streamId); o.write(streamId shr 8); o.write(streamId shr 16); o.write(streamId shr 24) // little endian
            if (extended) writeInt(o, ts.toInt())
            var off = 0
            var header = 12
            while (off < payload.size) {
                if (off > 0) {
                    o.write(0xC0 or (csid and 0x3F)) // fmt 3: continuação
                    header += 1
                    if (extended) { writeInt(o, ts.toInt()); header += 4 }
                }
                val n = minOf(outChunkSize, payload.size - off)
                o.write(payload, off, n)
                off += n
            }
            o.flush()
            bytesSent += payload.size + header
        }
    }

    private fun writeInt(o: OutputStream, v: Int) {
        o.write(v ushr 24); o.write(v shr 16); o.write(v shr 8); o.write(v)
    }

    // ------------------------------------------------------------------ leitura

    private class ChunkState {
        var ts = 0L
        var len = 0
        var type = 0
        var streamId = 0
        var buffer: ByteArray? = null
        var filled = 0
    }

    private class Message(val type: Int, val payload: ByteArray)

    private fun readMessage(): Message {
        while (true) {
            val b0 = readByte()
            val fmt = b0 shr 6
            var csid = b0 and 0x3F
            if (csid == 0) csid = 64 + readByte()
            else if (csid == 1) csid = 64 + readByte() + readByte() * 256
            val st = inStates.getOrPut(csid) { ChunkState() }
            if (fmt <= 2) {
                var ts = read24().toLong()
                if (fmt <= 1) {
                    st.len = read24()
                    st.type = readByte()
                    if (fmt == 0) st.streamId = readByte() or (readByte() shl 8) or (readByte() shl 16) or (readByte() shl 24)
                }
                if (ts == 0xFFFFFFL) ts = readInt().toLong() and 0xFFFFFFFFL
                st.ts = if (fmt == 0) ts else st.ts + ts
            }
            if (st.buffer == null) {
                st.buffer = ByteArray(st.len)
                st.filled = 0
            }
            val buf = st.buffer!!
            val n = minOf(inChunkSize, buf.size - st.filled)
            readFully(buf, st.filled, n)
            st.filled += n
            countReceived(n)
            if (st.filled >= buf.size) {
                st.buffer = null
                val msg = Message(st.type, buf)
                if (handleControl(msg)) continue
                return msg
            }
        }
    }

    /** Trata mensagens de controle de protocolo. Retorna true se consumida. */
    private fun handleControl(msg: Message): Boolean {
        val p = msg.payload
        when (msg.type) {
            1 -> { inChunkSize = be32(p, 0) and 0x7FFFFFFF; return true }
            3 -> return true // acknowledgement do servidor
            5 -> { windowAckSize = be32(p, 0).toLong() and 0xFFFFFFFFL; return true }
            6 -> return true // set peer bandwidth
            4 -> { // user control
                val event = ((p[0].toInt() and 0xFF) shl 8) or (p[1].toInt() and 0xFF)
                if (event == 6 && p.size >= 6) { // PingRequest -> PingResponse
                    sendMessage(2, 4, 0, 0, byteArrayOf(0, 7) + p.copyOfRange(2, 6))
                }
                return true
            }
        }
        return false
    }

    private fun countReceived(n: Int) {
        bytesReceived += n
        if (bytesReceived - lastAckSent >= windowAckSize / 2) {
            lastAckSent = bytesReceived
            val v = bytesReceived.toInt()
            sendMessage(2, 3, 0, 0, byteArrayOf((v ushr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))
        }
    }

    private fun expectResult(cmd: String) {
        waitFor { name, values ->
            when (name) {
                "_result" -> true
                "_error" -> {
                    val info = values.getOrNull(3) as? Map<*, *>
                    throw RtmpException("Servidor recusou '$cmd': ${info?.get("code") ?: info?.get("description") ?: "erro"}")
                }
                else -> null
            }
        }
    }

    /** Lê comandos até que [check] retorne não-nulo. */
    private fun <T : Any> waitFor(check: (name: String, values: List<Any?>) -> T?): T {
        while (true) {
            val msg = readMessage()
            if (msg.type != 20) continue
            val values = runCatching { Amf0.decodeAll(msg.payload) }.getOrNull() ?: continue
            val name = values.firstOrNull() as? String ?: continue
            check(name, values)?.let { return it }
        }
    }

    private fun readerLoop() {
        try {
            while (!closed) {
                val msg = readMessage()
                if (msg.type == 20) {
                    val values = runCatching { Amf0.decodeAll(msg.payload) }.getOrNull() ?: continue
                    val info = values.getOrNull(3) as? Map<*, *>
                    val code = info?.get("code") as? String ?: continue
                    onLog("Servidor: $code")
                    if (code.contains("Failed") || code.contains("Error") || code.contains("Unpublish")) {
                        failure = "Servidor encerrou a publicação: $code"
                        runCatching { socket?.close() }
                    }
                }
            }
        } catch (e: IOException) {
            if (!closed) failure = "Conexão com o servidor perdida: ${e.message}"
        }
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("Servidor RTMP fechou a conexão")
        return b
    }

    private fun read24(): Int = (readByte() shl 16) or (readByte() shl 8) or readByte()
    private fun readInt(): Int = (readByte() shl 24) or (readByte() shl 16) or (readByte() shl 8) or readByte()

    private fun readFully(buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        var o = off
        while (o < off + len) {
            val n = input.read(buf, o, off + len - o)
            if (n < 0) throw EOFException("Servidor RTMP fechou a conexão")
            o += n
        }
    }

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
