package com.rcan88.live

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.rcan88.core.relay.RelayState

/** Tela de operação: configurar, iniciar/parar e acompanhar a live. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var log: TextView
    private lateinit var cameraUrl: EditText
    private lateinit var cameraUser: EditText
    private lateinit var cameraPass: EditText
    private lateinit var serverUrl: EditText
    private lateinit var streamKey: EditText
    private lateinit var audio: CheckBox
    private lateinit var splitNetwork: CheckBox
    private lateinit var startBtn: Button

    private val observer: (LiveState.Snapshot) -> Unit = ::render

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        status = findViewById(R.id.status)
        stats = findViewById(R.id.stats)
        log = findViewById(R.id.log)
        cameraUrl = findViewById(R.id.cameraUrl)
        cameraUser = findViewById(R.id.cameraUser)
        cameraPass = findViewById(R.id.cameraPass)
        serverUrl = findViewById(R.id.serverUrl)
        streamKey = findViewById(R.id.streamKey)
        audio = findViewById(R.id.audio)
        splitNetwork = findViewById(R.id.splitNetwork)
        startBtn = findViewById(R.id.start)

        cameraUrl.setText(prefs.cameraUrl)
        cameraUser.setText(prefs.cameraUser)
        cameraPass.setText(prefs.cameraPass)
        serverUrl.setText(prefs.serverUrl)
        streamKey.setText(prefs.streamKey)
        audio.isChecked = prefs.audio
        splitNetwork.isChecked = prefs.splitNetwork

        startBtn.setOnClickListener { onStartClicked() }
        findViewById<Button>(R.id.stop).setOnClickListener { LiveService.stop(this) }
        findViewById<Button>(R.id.batterySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        LiveState.observe(observer)
    }

    override fun onPause() {
        LiveState.remove(observer)
        save()
        super.onPause()
    }

    private fun save() {
        prefs.cameraUrl = cameraUrl.text.toString().trim()
        prefs.cameraUser = cameraUser.text.toString().trim()
        prefs.cameraPass = cameraPass.text.toString()
        prefs.serverUrl = serverUrl.text.toString().trim().ifEmpty { Prefs.DEFAULT_SERVER }
        prefs.streamKey = streamKey.text.toString().trim()
        prefs.audio = audio.isChecked
        prefs.splitNetwork = splitNetwork.isChecked
    }

    private fun onStartClicked() {
        save()
        val error = when {
            !prefs.cameraUrl.startsWith("rtsp://", ignoreCase = true) -> "A URL da câmera deve começar com rtsp://"
            !prefs.serverUrl.startsWith("rtmp", ignoreCase = true) -> "A URL do servidor deve começar com rtmp:// ou rtmps://"
            prefs.streamKey.isEmpty() -> "Informe a chave da transmissão do YouTube Studio"
            else -> null
        }
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        LiveState.update { it.copy(log = emptyList()) }
        LiveService.start(this)
    }

    private fun render(s: LiveState.Snapshot) {
        status.text = LiveService.stateLabel(s.state)
        status.setBackgroundColor(
            when (s.state) {
                RelayState.LIVE -> Color.parseColor("#C62828")
                RelayState.CONNECTING_CAMERA, RelayState.CONNECTING_SERVER -> Color.parseColor("#F9A825")
                RelayState.RECONNECTING -> Color.parseColor("#EF6C00")
                else -> Color.parseColor("#455A64")
            }
        )
        val running = s.state != RelayState.IDLE && s.state != RelayState.STOPPED
        startBtn.isEnabled = !running

        val st = s.stats
        stats.text = buildString {
            if (st != null && running) {
                appendLine("Tempo no ar : ${LiveService.formatUptime(st.uptimeSec)}")
                appendLine("Taxa        : ${st.bitrateKbps} kbps   ${st.fps} fps")
                appendLine("Atraso fila : ${st.queueMs} ms   descartados: ${st.droppedFrames}")
                appendLine("Reconexões  : ${st.reconnects}")
                st.video?.let { appendLine("Vídeo       : $it") }
                st.audio?.let { appendLine("Áudio       : $it") }
            }
            if (s.batteryPct >= 0) {
                val temp = if (s.batteryTempC.isNaN()) "" else "  %.1f°C".format(s.batteryTempC)
                val warn = if (!s.batteryTempC.isNaN() && s.batteryTempC >= 42f) "  ⚠ QUENTE" else ""
                appendLine("Bateria     : ${s.batteryPct}%${if (s.charging) " (carregando)" else ""}$temp$warn")
            }
            if (s.network.isNotEmpty()) appendLine("Rede        : ${s.network}")
        }.trimEnd()
        log.text = s.log.joinToString("\n")
    }
}
