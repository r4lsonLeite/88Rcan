package com.rcan88.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.rcan88.core.relay.RelayConfig
import com.rcan88.core.relay.RelayEngine
import com.rcan88.core.relay.RelayListener
import com.rcan88.core.relay.RelayState
import com.rcan88.core.relay.RelayStats
import javax.net.SocketFactory

/**
 * Serviço em primeiro plano que mantém a live rodando com a tela apagada.
 * Segura CPU (WakeLock) e Wi-Fi (WifiLock) acordados e mostra o status na notificação.
 */
class LiveService : Service() {

    private var engine: RelayEngine? = null
    private var router: NetworkRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLive()
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null) return START_STICKY

        createChannel()
        val notification = buildNotification("Conectando…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireLocks()
        val prefs = Prefs(this)
        Thread({ startLive(prefs) }, "live-start").start()
        return START_STICKY
    }

    private fun startLive(prefs: Prefs) {
        val r = NetworkRouter(this)
        router = r
        var sourceFactory: SocketFactory = SocketFactory.getDefault()
        var targetFactory: SocketFactory = SocketFactory.getDefault()
        if (prefs.splitNetwork) {
            LiveState.log("Separando redes: câmera via Wi-Fi, YouTube via dados móveis")
            r.wifiSocketFactory()?.let { sourceFactory = it } ?: LiveState.log("Wi-Fi não encontrado — usando rede padrão")
            r.cellularSocketFactory()?.let { targetFactory = it } ?: LiveState.log("Dados móveis indisponíveis — usando rede padrão")
        }
        val config = RelayConfig(
            sourceUrl = prefs.cameraUrl.trim(),
            sourceUser = prefs.cameraUser.trim().ifEmpty { null },
            sourcePassword = prefs.cameraPass.ifEmpty { null },
            targetUrl = prefs.serverUrl.trim().trimEnd('/') + "/" + prefs.streamKey.trim(),
            enableAudio = prefs.audio,
            sourceSocketFactory = sourceFactory,
            targetSocketFactory = targetFactory,
        )
        val e = RelayEngine(config, object : RelayListener {
            override fun onState(state: RelayState, message: String?) {
                LiveState.update { it.copy(state = state, message = message) }
                message?.let { LiveState.log(it) }
                notifyStatus(force = true)
            }

            override fun onStats(stats: RelayStats) {
                val battery = readBattery()
                LiveState.update {
                    it.copy(
                        stats = stats,
                        batteryPct = battery.first,
                        batteryTempC = battery.second,
                        charging = battery.third,
                        network = r.describeDefault(),
                    )
                }
                notifyStatus(force = false)
            }

            override fun onLog(message: String) = LiveState.log(message)
        })
        engine = e
        e.start()
    }

    private fun stopLive() {
        engine?.let { e -> Thread { e.stop() }.start() }
        engine = null
        router?.release()
        router = null
        releaseLocks()
        LiveState.update { it.copy(state = RelayState.STOPPED, message = null) }
    }

    override fun onDestroy() {
        stopLive()
        super.onDestroy()
    }

    // ------------------------------------------------------------ energia

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "88rcan:live").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "88rcan:live").apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }

    /** (nível %, temperatura °C, carregando) */
    private fun readBattery(): Triple<Int, Float, Boolean> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Triple(-1, Float.NaN, false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Triple(if (level >= 0) level * 100 / scale else -1, temp, charging)
    }

    // ------------------------------------------------------------ notificação

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transmissão ao vivo", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notifyStatus(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 5_000) return
        lastNotify = now
        val s = LiveState.current
        val text = when (s.state) {
            RelayState.LIVE -> s.stats?.let { st ->
                "AO VIVO ${formatUptime(st.uptimeSec)} • ${st.bitrateKbps} kbps • ${st.fps} fps" +
                    (if (s.batteryPct >= 0) " • bateria ${s.batteryPct}%" else "")
            } ?: "AO VIVO"
            else -> stateLabel(s.state)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "live"
        private const val NOTIF_ID = 88
        const val ACTION_STOP = "com.rcan88.live.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, LiveService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveService::class.java).setAction(ACTION_STOP))
        }

        fun formatUptime(sec: Long) = "%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)

        fun stateLabel(s: RelayState) = when (s) {
            RelayState.IDLE -> "Parado"
            RelayState.CONNECTING_CAMERA -> "Conectando à câmera…"
            RelayState.CONNECTING_SERVER -> "Conectando ao YouTube…"
            RelayState.LIVE -> "AO VIVO"
            RelayState.RECONNECTING -> "Reconectando…"
            RelayState.STOPPED -> "Parado"
        }
    }
}
