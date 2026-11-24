package com.rcan88.core.rtsp

import java.security.MessageDigest
import java.util.Base64

/** Autenticação RTSP Basic e Digest (RFC 2617), usada pela maioria das câmeras IP. */
class RtspAuth(private val user: String, private val password: String) {
    private var scheme: String? = null
    private var realm = ""
    private var nonce = ""
    private var opaque: String? = null
    private var qop: String? = null
    private var nc = 0

    val hasChallenge: Boolean get() = scheme != null

    /** Recebe os headers WWW-Authenticate de uma resposta 401. Retorna false se não suportado. */
    fun onChallenge(headers: List<String>): Boolean {
        val digest = headers.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
        val basic = headers.firstOrNull { it.trim().startsWith("Basic", ignoreCase = true) }
        when {
            digest != null -> {
                val params = parseParams(digest.trim().substring(6))
                scheme = "Digest"
                realm = params["realm"] ?: ""
                nonce = params["nonce"] ?: ""
                opaque = params["opaque"]
                qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
                nc = 0
            }
            basic != null -> {
                scheme = "Basic"
                realm = parseParams(basic.trim().substring(5))["realm"] ?: ""
            }
            else -> return false
        }
        return true
    }

    fun header(method: String, uri: String): String? = when (scheme) {
        "Basic" -> "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        "Digest" -> {
            val ha1 = md5("$user:$realm:$password")
            val ha2 = md5("$method:$uri")
            val sb = StringBuilder()
            sb.append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (qop != null) {
                nc++
                val ncHex = "%08x".format(nc)
                val cnonce = md5(System.nanoTime().toString()).substring(0, 16)
                val response = md5("$ha1:$nonce:$ncHex:$cnonce:$qop:$ha2")
                sb.append(", response=\"$response\", qop=$qop, nc=$ncHex, cnonce=\"$cnonce\"")
            } else {
                sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
            }
            opaque?.let { sb.append(", opaque=\"$it\"") }
            sb.toString()
        }
        else -> null
    }

    companion object {
        fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun parseParams(s: String): Map<String, String> {
            val out = HashMap<String, String>()
            val re = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
            for (m in re.findAll(s)) {
                out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
            }
            return out
        }
    }
}
