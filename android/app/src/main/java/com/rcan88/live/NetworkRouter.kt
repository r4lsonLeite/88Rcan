package com.rcan88.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Escolhe por qual rede cada lado da transmissão sai.
 *
 * Cenário "câmera com Wi-Fi próprio": o celular entra no Wi-Fi da câmera (que não tem internet).
 * Por padrão o Android jogaria todo o tráfego nesse Wi-Fi e a live não sairia. Aqui pedimos as
 * duas redes explicitamente e amarramos cada socket na sua:
 *   - câmera (RTSP)  → rede Wi-Fi
 *   - YouTube (RTMP) → rede de dados móveis
 *
 * Cenário "câmera no roteador/hotspot do celular": não precisa de nada, a rota padrão já serve.
 */
class NetworkRouter(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val callbacks = ArrayList<ConnectivityManager.NetworkCallback>()

    fun cellularSocketFactory(timeoutMs: Long = 10_000): SocketFactory? =
        acquire(NetworkCapabilities.TRANSPORT_CELLULAR, requireInternet = true, timeoutMs)?.socketFactory

    fun wifiSocketFactory(timeoutMs: Long = 10_000): SocketFactory? =
        acquire(NetworkCapabilities.TRANSPORT_WIFI, requireInternet = false, timeoutMs)?.socketFactory

    /** Descrição curta da rede padrão atual, para o painel. */
    fun describeDefault(): String {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "sem rede"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dados móveis"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "cabo"
            else -> "outra"
        }
        val up = caps.linkUpstreamBandwidthKbps
        return if (up > 0) "$type (~${up / 1000} Mbps up estimado)" else type
    }

    private fun acquire(transport: Int, requireInternet: Boolean, timeoutMs: Long): Network? {
        val builder = NetworkRequest.Builder().addTransportType(transport)
        if (requireInternet) builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        else builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Wi-Fi da câmera não tem internet
        val latch = CountDownLatch(1)
        var found: Network? = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found = network
                latch.countDown()
            }
        }
        cm.requestNetwork(builder.build(), cb)
        synchronized(callbacks) { callbacks.add(cb) }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return found
    }

    /** Libera as redes pedidas (o Android pode desligar os dados móveis de novo). */
    fun release() {
        synchronized(callbacks) {
            callbacks.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
            callbacks.clear()
        }
    }
}
