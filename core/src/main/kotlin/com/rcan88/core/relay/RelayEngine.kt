package com.rcan88.core.relay

import com.rcan88.core.media.AudioConfig
import com.rcan88.core.media.AudioFrame
import com.rcan88.core.media.VideoCodec
import com.rcan88.core.media.VideoConfig
import com.rcan88.core.media.VideoFrame
import com.rcan88.core.rtmp.FlvTags
import com.rcan88.core.rtmp.RtmpPublisher
import com.rcan88.core.rtsp.RtspClient
import com.rcan88.core.rtsp.RtspException
import com.rcan88.core.rtsp.RtspListener
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

data class RelayConfig(
    /** rtsp://[usuario:senha@]ip:porta/caminho */
    val sourceUrl: String,
    val sourceUser: String? = null,
    val sourcePassword: String? = null,
    /** rtmp(s)://servidor/app/chave — para o YouTube: rtmps://a.rtmps.youtube.com/live2/<chave> */
    val targetUrl: String,
    val enableAudio: Boolean = true,
    /** Fábrica de sockets da câmera (no Android: rede Wi-Fi). */
    val sourceSocketFactory: SocketFactory = SocketFactory.getDefault(),
    /** Fábrica de sockets da internet (no Android: rede de dados móveis). */
    val targetSocketFactory: SocketFactory = SocketFactory.getDefault(),
    /** Atraso máximo acumulado antes de descartar frames (rede lenta). */
    val maxQueueMs: Long = 3_000,
    /** Esperas entre tentativas de reconexão. */
    val reconnectDelaysMs: List<Long> = listOf(1_000, 2_000, 5_000, 10_000),
    val timeoutMs: Int = 10_000,
)

enum class RelayState { IDLE, CONNECTING_CAMERA, CONNECTING_SERVER, LIVE, RECONNECTING, STOPPED }

data class RelayStats(
    val state: RelayState,
    val uptimeSec: Long,
    val bitrateKbps: Int,
    val fps: Int,
    val queueMs: Long,
    val droppedFrames: Long,
    val reconnects: Int,
    val video: String?,
    val audio: String?,
    val lastError: String?,
)

interface RelayListener {
    fun onState(state: RelayState, message: String? = null) {}
    fun onStats(stats: RelayStats) {}
    fun onLog(message: String) {}
}

/**
 * Motor de retransmissão: câmera (RTSP) → celular → YouTube (RTMP/RTMPS), sem recodificar.
 *
 * - Reenvia o H.264/H.265 (+AAC) da câmera como está: CPU e bateria mínimas, qualidade original.
 * - Reconecta sozinho (câmera ou internet) com backoff.
 * - Se a internet móvel ficar lenta e a fila passar de [RelayConfig.maxQueueMs], descarta
 *   frames até o próximo keyframe para manter a live "ao vivo" em vez de acumular atraso.
 */
class RelayEngine(private val config: RelayConfig, private val listener: RelayListener) {

    private sealed class Item {
        class Video(val frame: VideoFrame) : Item()
        class Audio(val frame: AudioFrame) : Item()
        class VConfig(val config: VideoConfig) : Item()
        class AConfig(val config: AudioConfig) : Item()
        class SourceFailed(val error: Throwable) : Item()
    }

    @Volatile private var running = false
    private var supervisor: Thread? = null
    @Volatile private var rtsp: RtspClient? = null
    @Volatile private var rtmp: RtmpPublisher? = null

    @Volatile var state = RelayState.IDLE
        private set
    private var reconnects = 0
    private var droppedFrames = 0L
    private var lastError: String? = null
    private var liveSinceMs = 0L
    private var videoDesc: String? = null
    private var audioDesc: String? = null

    fun start() {
        if (running) return
        running = true
        supervisor = Thread(::supervise, "relay-supervisor").apply { start() }
    }

    fun stop() {
        running = false
        rtsp?.stop()
        rtmp?.close()
        supervisor?.interrupt()
        supervisor?.join(3_000)
        setState(RelayState.STOPPED)
    }

    private fun supervise() {
        var attempt = 0
        while (running) {
            val sessionStart = System.currentTimeMillis()
            try {
                runSession()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                lastError = e.message ?: e.javaClass.simpleName
                listener.onLog("Erro: $lastError")
            } finally {
                rtsp?.stop(); rtsp = null
                rtmp?.close(); rtmp = null
            }
            if (!running) break
            // Sessão longa e saudável zera o backoff
            if (System.currentTimeMillis() - sessionStart > 60_000) attempt = 0
            val delay = config.reconnectDelaysMs[minOf(attempt, config.reconnectDelaysMs.lastIndex)]
            attempt++
            reconnects++
            setState(RelayState.RECONNECTING, "Nova tentativa em ${delay / 1000}s — $lastError")
            try { Thread.sleep(delay) } catch (_: InterruptedException) { break }
        }
        setState(RelayState.STOPPED)
    }

    private fun runSession() {
        val queue = LinkedBlockingDeque<Item>()
        val gate = QueueGate(config.maxQueueMs)
        setState(RelayState.CONNECTING_CAMERA)

        val client = RtspClient(
            url = config.sourceUrl,
            user = config.sourceUser,
            password = config.sourcePassword,
            enableAudio = config.enableAudio,
            socketFactory = config.sourceSocketFactory,
            timeoutMs = config.timeoutMs,
            listener = object : RtspListener {
                override fun onVideoConfig(config: VideoConfig) { queue.add(Item.VConfig(config)) }
                override fun onAudioConfig(config: AudioConfig) { queue.add(Item.AConfig(config)) }
                override fun onVideoFrame(frame: VideoFrame) {
                    if (gate.acceptVideo(frame, queue.size)) queue.add(Item.Video(frame)) else droppedFrames++
                }
                override fun onAudioFrame(frame: AudioFrame) {
                    if (gate.acceptAudio(frame)) queue.add(Item.Audio(frame))
                }
                override fun onLog(message: String) = listener.onLog(message)
            },
        )
        rtsp = client
        val sourceThread = Thread({
            try { client.run(); queue.add(Item.SourceFailed(RtspException("Câmera desconectada"))) }
            catch (e: Throwable) { queue.add(Item.SourceFailed(e)) }
        }, "rtsp-source").apply { isDaemon = true; start() }

        try {
            pump(queue, gate)
        } finally {
            client.stop()
            sourceThread.join(2_000)
        }
    }

    /** Lê a fila e envia ao servidor. Conecta ao servidor só quando a câmera já entregou SPS/PPS. */
    private fun pump(queue: LinkedBlockingDeque<Item>, gate: QueueGate) {
        var videoCfg: VideoConfig? = null
        var audioCfg: AudioConfig? = null
        var publisher: RtmpPublisher? = null
        var baseTs = -1L
        var lastVideoTs = 0L
        var lastAudioTs = 0L

        var statWindowStart = System.nanoTime()
        var statBytesStart = 0L
        var statFrames = 0
        val connectDeadline = System.currentTimeMillis() + config.timeoutMs * 2

        while (running) {
            val item = queue.poll(500, TimeUnit.MILLISECONDS)
            if (publisher == null && System.currentTimeMillis() > connectDeadline) {
                throw RtspException("A câmera não enviou vídeo válido (sem parameter sets ou keyframe)")
            }
            when (item) {
                null -> Unit
                is Item.SourceFailed -> throw item.error
                is Item.VConfig -> {
                    val changed = videoCfg != null
                    videoCfg = item.config
                    videoDesc = item.config.toString()
                    listener.onLog("Vídeo: $videoDesc")
                    if (changed && publisher != null) publisher.sendVideo(FlvTags.videoSequenceHeader(item.config), lastVideoTs)
                }
                is Item.AConfig -> {
                    audioCfg = item.config
                    audioDesc = item.config.toString()
                    listener.onLog("Áudio: $audioDesc")
                }
                is Item.Video -> {
                    gate.onDequeued(item.frame.ptsMs)
                    val vcfg = videoCfg ?: continue
                    if (publisher == null) {
                        if (!item.frame.keyframe) continue // começa a live sempre num keyframe
                        setState(RelayState.CONNECTING_SERVER)
                        publisher = RtmpPublisher(
                            config.targetUrl, config.targetSocketFactory, config.timeoutMs, listener::onLog,
                            fourCcList = if (vcfg.codec == VideoCodec.H265) listOf("hvc1") else emptyList(),
                        )
                        rtmp = publisher
                        publisher.connect()
                        publisher.sendMetadata(FlvTags.metadata(vcfg, audioCfg))
                        publisher.sendVideo(FlvTags.videoSequenceHeader(vcfg), 0)
                        audioCfg?.let { publisher.sendAudio(FlvTags.aacSequenceHeader(it), 0) }
                        baseTs = item.frame.ptsMs
                        liveSinceMs = System.currentTimeMillis()
                        statBytesStart = publisher.bytesSent
                        setState(RelayState.LIVE)
                        // O que acumulou durante a conexão é escoado normalmente; se passar de
                        // maxQueueMs o QueueGate passa a descartar até o próximo keyframe.
                    }
                    val ts = maxOf(item.frame.ptsMs - baseTs, lastVideoTs)
                    lastVideoTs = ts
                    publisher.sendVideo(FlvTags.videoFrame(vcfg.codec, item.frame.nalus, item.frame.keyframe), ts)
                    statFrames++
                }
                is Item.Audio -> {
                    val p = publisher ?: continue
                    if (audioCfg == null) continue
                    val rel = item.frame.ptsMs - baseTs
                    if (rel < 0) continue
                    val ts = maxOf(rel, lastAudioTs)
                    lastAudioTs = ts
                    p.sendAudio(FlvTags.aacFrame(item.frame.data), ts)
                }
            }

            val now = System.nanoTime()
            if (now - statWindowStart >= 1_000_000_000L) {
                val secs = (now - statWindowStart) / 1e9
                val bytes = (publisher?.bytesSent ?: 0L) - statBytesStart
                listener.onStats(
                    RelayStats(
                        state = state,
                        uptimeSec = if (state == RelayState.LIVE) (System.currentTimeMillis() - liveSinceMs) / 1000 else 0,
                        bitrateKbps = (bytes * 8 / 1000 / secs).toInt(),
                        fps = (statFrames / secs).toInt(),
                        queueMs = gate.queuedMs(),
                        droppedFrames = droppedFrames,
                        reconnects = reconnects,
                        video = videoDesc,
                        audio = audioDesc,
                        lastError = lastError,
                    )
                )
                statWindowStart = now
                statBytesStart = publisher?.bytesSent ?: 0L
                statFrames = 0
            }
        }
    }

    private fun setState(s: RelayState, message: String? = null) {
        if (s == state && message == null) return
        state = s
        listener.onState(s, message)
    }

    /**
     * Controle de congestionamento: mede quantos ms de vídeo estão esperando envio.
     * Acima do limite, joga fora tudo até o próximo keyframe (áudio incluso, p/ manter sincronia).
     */
    private class QueueGate(private val maxMs: Long) {
        @Volatile private var newestQueued = -1L
        @Volatile private var lastSent = -1L
        @Volatile private var dropping = false

        fun acceptVideo(f: VideoFrame, queueSize: Int): Boolean {
            if (dropping) {
                if (!f.keyframe) return false
                dropping = false
            } else if (lastSent >= 0 && f.ptsMs - lastSent > maxMs && queueSize > 0) {
                dropping = !f.keyframe
                if (dropping) return false
            }
            newestQueued = f.ptsMs
            return true
        }

        fun acceptAudio(f: AudioFrame): Boolean = !dropping

        fun onDequeued(pts: Long) { lastSent = pts }

        fun queuedMs(): Long = if (newestQueued < 0 || lastSent < 0) 0 else maxOf(0, newestQueued - lastSent)

        fun reset() { lastSent = -1; newestQueued = -1; dropping = false }
    }
}
