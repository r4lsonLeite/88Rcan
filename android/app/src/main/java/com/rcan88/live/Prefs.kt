package com.rcan88.live

import android.content.Context

/**
 * Configuração salva no aparelho.
 * Obs.: a chave da transmissão fica em SharedPreferences privadas do app (não acessível a outros
 * apps, mas não criptografada). Para produção, migrar para Android Keystore.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var cameraUrl: String
        get() = sp.getString("cameraUrl", "") ?: ""
        set(v) = sp.edit().putString("cameraUrl", v).apply()
    var cameraUser: String
        get() = sp.getString("cameraUser", "") ?: ""
        set(v) = sp.edit().putString("cameraUser", v).apply()
    var cameraPass: String
        get() = sp.getString("cameraPass", "") ?: ""
        set(v) = sp.edit().putString("cameraPass", v).apply()
    var serverUrl: String
        get() = sp.getString("serverUrl", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("serverUrl", v).apply()
    var streamKey: String
        get() = sp.getString("streamKey", "") ?: ""
        set(v) = sp.edit().putString("streamKey", v).apply()
    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) = sp.edit().putBoolean("audio", v).apply()
    var splitNetwork: Boolean
        get() = sp.getBoolean("splitNetwork", false)
        set(v) = sp.edit().putBoolean("splitNetwork", v).apply()

    companion object {
        /** RTMPS (TLS na porta 443): passa melhor por redes móveis/firewalls que RTMP na 1935. */
        const val DEFAULT_SERVER = "rtmps://a.rtmps.youtube.com/live2"
    }
}
