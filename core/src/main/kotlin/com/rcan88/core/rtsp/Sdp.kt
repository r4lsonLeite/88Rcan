package com.rcan88.core.rtsp

data class SdpMedia(
    val type: String,            // "video" | "audio"
    val payloadType: Int,
    val encoding: String,        // "H264", "H265", "MPEG4-GENERIC", "PCMA", ...
    val clockRate: Int,
    val channels: Int,
    val fmtp: Map<String, String>,
    val control: String?,
)

data class SessionDescription(val control: String?, val media: List<SdpMedia>)

object SdpParser {

    fun parse(text: String): SessionDescription {
        var sessionControl: String? = null
        val media = ArrayList<SdpMedia>()

        var type: String? = null
        var pt = -1
        var encoding = ""
        var clock = 0
        var channels = 1
        var fmtp = emptyMap<String, String>()
        var control: String? = null

        fun commit() {
            val t = type ?: return
            media.add(SdpMedia(t, pt, encoding.uppercase(), clock, channels, fmtp, control))
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.length < 2 || line[1] != '=') continue
            val value = line.substring(2)
            when (line[0]) {
                'm' -> {
                    commit()
                    val parts = value.split(' ')
                    type = parts[0]
                    pt = parts.getOrNull(3)?.toIntOrNull() ?: -1
                    encoding = ""; clock = 0; channels = 1; fmtp = emptyMap(); control = null
                    // payload types estáticos
                    when (pt) {
                        0 -> { encoding = "PCMU"; clock = 8000 }
                        8 -> { encoding = "PCMA"; clock = 8000 }
                        14 -> { encoding = "MPA"; clock = 90000 }
                        26 -> { encoding = "JPEG"; clock = 90000 }
                    }
                }
                'a' -> {
                    val colon = value.indexOf(':')
                    val key = if (colon >= 0) value.substring(0, colon) else value
                    val v = if (colon >= 0) value.substring(colon + 1) else ""
                    when (key) {
                        "control" -> if (type == null) sessionControl = v else control = v
                        "rtpmap" -> if (type != null) {
                            val (ptStr, rest) = v.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                            if (ptStr.toIntOrNull() == pt) {
                                val enc = rest.split('/')
                                encoding = enc[0]
                                clock = enc.getOrNull(1)?.toIntOrNull() ?: 0
                                channels = enc.getOrNull(2)?.toIntOrNull() ?: 1
                            }
                        }
                        "fmtp" -> if (type != null) {
                            val (ptStr, rest) = v.split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                            if (ptStr.toIntOrNull() == pt) fmtp = parseFmtp(rest)
                        }
                    }
                }
            }
        }
        commit()
        return SessionDescription(sessionControl, media)
    }

    fun parseFmtp(s: String): Map<String, String> =
        s.split(';').mapNotNull { part ->
            val p = part.trim()
            if (p.isEmpty()) return@mapNotNull null
            val eq = p.indexOf('=')
            if (eq < 0) p.lowercase() to "" else p.substring(0, eq).trim().lowercase() to p.substring(eq + 1).trim()
        }.toMap()

    /** Resolve o atributo a=control em relação à URL base (RFC 2326 C.1.1). */
    fun resolveControl(base: String, control: String?): String {
        if (control.isNullOrEmpty() || control == "*") return base
        if (control.startsWith("rtsp://", ignoreCase = true) || control.startsWith("rtsps://", ignoreCase = true)) return control
        return if (base.endsWith("/")) base + control else "$base/$control"
    }
}
