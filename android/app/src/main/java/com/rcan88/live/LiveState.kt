package com.rcan88.live

import android.os.Handler
import android.os.Looper
import com.rcan88.core.relay.RelayState
import com.rcan88.core.relay.RelayStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Estado compartilhado entre o serviço (que transmite) e a tela (que mostra). */
object LiveState {
    data class Snapshot(
        val state: RelayState = RelayState.IDLE,
        val message: String? = null,
        val stats: RelayStats? = null,
        val batteryPct: Int = -1,
        val batteryTempC: Float = Float.NaN,
        val charging: Boolean = false,
        val network: String = "",
        val log: List<String> = emptyList(),
    )

    @Volatile var current = Snapshot()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun observe(l: (Snapshot) -> Unit) { listeners.add(l); l(current) }
    fun remove(l: (Snapshot) -> Unit) { listeners.remove(l) }

    @Synchronized
    fun update(f: (Snapshot) -> Snapshot) {
        current = f(current)
        val snap = current
        main.post { listeners.forEach { it(snap) } }
    }

    fun log(message: String) = update {
        val line = "${timeFmt.format(Date())} $message"
        it.copy(log = (listOf(line) + it.log).take(40))
    }
}
