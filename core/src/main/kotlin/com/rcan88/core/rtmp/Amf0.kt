package com.rcan88.core.rtmp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Codificação AMF0 (o suficiente para comandos RTMP e metadados onMetaData). */
object Amf0 {

    /** Marca um mapa para ser serializado como ECMA array (usado em onMetaData). */
    class EcmaArray(val entries: Map<String, Any?>)

    fun encode(vararg values: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach { write(out, it) }
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(0x05)
            is Number -> {
                out.write(0x00)
                out.write(ByteBuffer.allocate(8).putDouble(v.toDouble()).array())
            }
            is Boolean -> { out.write(0x01); out.write(if (v) 1 else 0) }
            is String -> { out.write(0x02); writeUtf8(out, v) }
            is EcmaArray -> {
                out.write(0x08)
                out.write(ByteBuffer.allocate(4).putInt(v.entries.size).array())
                writeProps(out, v.entries)
            }
            is List<*> -> { // strict array
                out.write(0x0A)
                out.write(ByteBuffer.allocate(4).putInt(v.size).array())
                v.forEach { write(out, it) }
            }
            is Map<*, *> -> {
                out.write(0x03)
                @Suppress("UNCHECKED_CAST")
                writeProps(out, v as Map<String, Any?>)
            }
            else -> throw IllegalArgumentException("Tipo AMF0 não suportado: ${v::class}")
        }
    }

    private fun writeProps(out: ByteArrayOutputStream, map: Map<String, Any?>) {
        map.forEach { (k, value) -> writeUtf8(out, k); write(out, value) }
        out.write(0); out.write(0); out.write(0x09)
    }

    private fun writeUtf8(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(b.size shr 8); out.write(b.size and 0xFF)
        out.write(b)
    }

    /** Decodifica todos os valores de um payload AMF0. */
    fun decodeAll(data: ByteArray): List<Any?> {
        val buf = ByteBuffer.wrap(data)
        val out = ArrayList<Any?>()
        while (buf.hasRemaining()) out.add(read(buf))
        return out
    }

    private fun read(buf: ByteBuffer): Any? = when (val type = buf.get().toInt() and 0xFF) {
        0x00 -> buf.double
        0x01 -> buf.get().toInt() != 0
        0x02 -> readUtf8(buf)
        0x03 -> readProps(buf)
        0x05, 0x06 -> null
        0x08 -> { buf.int; readProps(buf) }
        0x0A -> { val n = buf.int; List(n) { read(buf) } }
        0x0B -> { buf.double; buf.short; null } // date
        0x0C -> { val n = buf.int; ByteArray(n).also { buf.get(it) }.toString(Charsets.UTF_8) }
        else -> throw IllegalArgumentException("Marcador AMF0 desconhecido: $type")
    }

    private fun readProps(buf: ByteBuffer): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        while (true) {
            val key = readUtf8(buf)
            if (key.isEmpty() && buf.get(buf.position()).toInt() == 0x09) {
                buf.get()
                return map
            }
            map[key] = read(buf)
        }
    }

    private fun readUtf8(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        val b = ByteArray(len)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }
}
