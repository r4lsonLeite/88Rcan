package com.rcan88.core.cli

import com.rcan88.core.relay.RelayConfig
import com.rcan88.core.relay.RelayEngine
import com.rcan88.core.relay.RelayListener
import com.rcan88.core.relay.RelayState
import com.rcan88.core.relay.RelayStats
import kotlin.system.exitProcess

/**
 * Executa o motor de retransmissão sem Android (PC, notebook, Raspberry Pi).
 *
 *   ./gradlew run --args="--source rtsp://user:pass@192.168.1.10:554/stream1 \
 *                         --target rtmps://a.rtmps.youtube.com/live2/SUA-CHAVE"
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val source = opts["source"]
    val target = opts["target"]
    if (source == null || target == null) {
        System.err.println(
            """
            Uso: --source <rtsp://...> --target <rtmp(s)://servidor/app/chave>
                 [--user u] [--password p] [--no-audio] [--duration segundos] [--max-queue-ms 3000]
            """.trimIndent()
        )
        exitProcess(2)
    }
    val engine = RelayEngine(
        RelayConfig(
            sourceUrl = source,
            sourceUser = opts["user"],
            sourcePassword = opts["password"],
            targetUrl = target,
            enableAudio = !opts.containsKey("no-audio"),
            maxQueueMs = opts["max-queue-ms"]?.toLong() ?: 3_000,
        ),
        object : RelayListener {
            override fun onState(state: RelayState, message: String?) =
                println("[estado] $state${message?.let { " — $it" } ?: ""}")

            override fun onStats(stats: RelayStats) {
                if (stats.state == RelayState.LIVE) {
                    println(
                        "[live] ${stats.uptimeSec}s  ${stats.bitrateKbps} kbps  ${stats.fps} fps  " +
                            "fila ${stats.queueMs} ms  descartados ${stats.droppedFrames}  reconexões ${stats.reconnects}"
                    )
                }
            }

            override fun onLog(message: String) = println("[log] ${redact(message)}")
        },
    )
    Runtime.getRuntime().addShutdownHook(Thread { engine.stop() })
    engine.start()
    val duration = opts["duration"]?.toLong()
    if (duration != null) {
        Thread.sleep(duration * 1000)
        engine.stop()
        exitProcess(0)
    }
    Thread.currentThread().join()
}

private fun redact(s: String) = s.replace(Regex("(live2?/)[^\\s]+"), "$1****")

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val next = args.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) { out[key] = next; i += 2; continue }
            out[key] = ""
        }
        i++
    }
    return out
}
